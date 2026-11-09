// Yobareta Nurse Station Desk Client
// Audio capture via Web Audio API + Automatic VAD Speech Slicer + Live Queue Dashboard

class NurseStationClient {
  constructor() {
    this.isListening = false;
    this.audioContext = null;
    this.mediaStream = null;
    this.analyser = null;
    this.processor = null;

    // VAD State
    this.sampleRate = 16000;
    this.buffer = [];
    this.preRoll = [];
    this.isVoiced = false;
    this.silenceSamples = 0;
    this.ambientDb = -50.0;

    this.initElements();
    this.bindEvents();
    this.refreshQueue();
    setInterval(() => this.refreshQueue(), 4000);
  }

  initElements() {
    this.startMicBtn = document.getElementById('startMicBtn');
    this.vuFill = document.getElementById('vuFill');
    this.dbFsText = document.getElementById('dbFsText');
    this.micStatusBadge = document.getElementById('micStatusBadge');
    this.transcriptLog = document.getElementById('transcriptLog');
    this.queueTbody = document.getElementById('queueTbody');
    this.simText = document.getElementById('simText');
    this.simBtn = document.getElementById('simBtn');
    this.qrCanvas = document.getElementById('qrCanvas');
    this.qrLink = document.getElementById('qrLink');
  }

  bindEvents() {
    this.startMicBtn.addEventListener('click', () => this.toggleMicrophone());
    this.simBtn.addEventListener('click', () => this.simulateAnnouncement());
    this.generatePatientQrCode();
  }

  async toggleMicrophone() {
    if (this.isListening) {
      this.stopMicrophone();
    } else {
      await this.startMicrophone();
    }
  }

  async startMicrophone() {
    try {
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          channelCount: 1,
          sampleRate: 16000,
          echoCancellation: true,
          noiseSuppression: false,
          autoGainControl: true
        }
      });

      this.audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
      const source = this.audioContext.createMediaStreamSource(this.mediaStream);

      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 512;
      source.connect(this.analyser);

      // ScriptProcessor for 100ms sample frames
      this.processor = this.audioContext.createScriptProcessor(2048, 1, 1);
      this.processor.onaudioprocess = (e) => this.handleAudioProcess(e);
      source.connect(this.processor);
      this.processor.connect(this.audioContext.destination);

      this.isListening = true;
      this.startMicBtn.textContent = 'Stop Desk Mic / マイク停止';
      this.startMicBtn.className = 'btn btn-danger';
      this.micStatusBadge.textContent = 'Active (Listening for calls)';
      this.micStatusBadge.className = 'status-badge connected';
    } catch (err) {
      alert(`Microphone access error: ${err.message}`);
    }
  }

  stopMicrophone() {
    this.isListening = false;
    if (this.processor) {
      this.processor.disconnect();
      this.processor = null;
    }
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach((t) => t.stop());
      this.mediaStream = null;
    }
    if (this.audioContext) {
      this.audioContext.close();
      this.audioContext = null;
    }
    this.startMicBtn.textContent = 'Start Desk Mic / 受付マイク開始';
    this.startMicBtn.className = 'btn btn-primary';
    this.micStatusBadge.textContent = 'Idle';
    this.micStatusBadge.className = 'status-badge';
    this.vuFill.style.width = '0%';
    this.dbFsText.textContent = '-60 dBFS';
  }

  handleAudioProcess(event) {
    if (!this.isListening) return;

    const inputData = event.inputBuffer.getChannelData(0);
    // Compute RMS
    let sum = 0;
    for (let i = 0; i < inputData.length; i++) {
      sum += inputData[i] * inputData[i];
    }
    const rms = Math.sqrt(sum / inputData.length);
    const db = rms > 0 ? 20 * Math.log10(rms) : -90;
    const clampedDb = Math.max(-80, Math.min(0, db));

    // Update VU meter UI
    const pct = Math.max(0, Math.min(100, (clampedDb + 60) * 2));
    this.vuFill.style.width = `${pct}%`;
    this.dbFsText.textContent = `${Math.round(clampedDb)} dBFS`;

    // Adaptive noise floor
    if (!this.isVoiced) {
      this.ambientDb = 0.95 * this.ambientDb + 0.05 * clampedDb;
    }

    const triggerThreshold = this.ambientDb + 7.5;
    const isLoud = clampedDb > triggerThreshold && clampedDb > -48;

    // Convert Float32 to Int16
    const pcm16 = new Int16Array(inputData.length);
    for (let i = 0; i < inputData.length; i++) {
      const s = Math.max(-1, Math.min(1, inputData[i]));
      pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }

    // Pre-roll management (keep ~1.0 s = 8 frames)
    if (!this.isVoiced) {
      this.preRoll.push(pcm16);
      if (this.preRoll.length > 8) {
        this.preRoll.shift();
      }

      if (isLoud) {
        this.isVoiced = true;
        this.buffer = [...this.preRoll, pcm16];
        this.silenceSamples = 0;
      }
    } else {
      this.buffer.push(pcm16);
      if (isLoud) {
        this.silenceSamples = 0;
      } else {
        this.silenceSamples += inputData.length;
      }

      // Cut segment if silence > 0.8s or total duration > 7.5s
      const totalSamples = this.buffer.length * inputData.length;
      const hitSilence = this.silenceSamples > 16000 * 0.8;
      const hitMax = totalSamples > 16000 * 7.5;

      if (hitSilence || hitMax) {
        if (totalSamples >= 16000 * 1.8) {
          this.dispatchAudioClip(this.buffer);
        }
        this.isVoiced = false;
        this.buffer = [];
        this.preRoll = [];
        this.silenceSamples = 0;
      }
    }
  }

  async dispatchAudioClip(chunks) {
    const totalLen = chunks.reduce((acc, c) => acc + c.length, 0);
    const merged = new Int16Array(totalLen);
    let offset = 0;
    for (const chunk of chunks) {
      merged.set(chunk, offset);
      offset += chunk.length;
    }

    const wavBlob = this.encodeWav(merged, 16000);
    this.logEvent('🎤 Announcement recorded, recognizing...');

    try {
      const formData = new FormData();
      formData.append('audio', wavBlob, 'announcement.wav');

      const resp = await fetch('/api/nurse/announce', {
        method: 'POST',
        body: formData
      });

      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const data = await resp.json();

      this.logEvent(`📢 Transcript: "${data.transcript}" → ${data.matches_count} matched patient(s)`);
      this.refreshQueue();
    } catch (err) {
      this.logEvent(`⚠️ Recognition error: ${err.message}`);
    }
  }

  async simulateAnnouncement() {
    const text = this.simText.value.trim();
    if (!text) return;

    this.simBtn.disabled = true;
    try {
      const resp = await fetch('/api/nurse/simulate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ transcript: text })
      });
      const data = await resp.json();
      this.logEvent(`📢 [Manual] "${text}" → ${data.matches_count} matched patient(s)`);
      this.refreshQueue();
    } catch (err) {
      this.logEvent(`⚠️ Simulation error: ${err.message}`);
    } finally {
      this.simBtn.disabled = false;
    }
  }

  async refreshQueue() {
    try {
      const resp = await fetch('/api/queue');
      if (!resp.ok) return;
      const queue = await resp.json();

      this.queueTbody.innerHTML = '';
      if (queue.length === 0) {
        this.queueTbody.innerHTML = '<tr><td colspan="5" style="text-align:center;color:var(--text-muted);">No patients currently in queue.</td></tr>';
        return;
      }

      queue.forEach((p) => {
        const tr = document.createElement('tr');
        const badgeClass = p.status === 'called' ? 'pill-called' : p.status === 'recall' ? 'pill-recall' : 'pill-waiting';
        tr.innerHTML = `
          <td><strong>#${p.ticket_number || '-'}</strong></td>
          <td>${p.name}</td>
          <td>${p.wait_minutes} min</td>
          <td><span class="pill-status ${badgeClass}">${p.status.toUpperCase()}</span></td>
          <td>${p.destination || '-'}</td>
        `;
        this.queueTbody.appendChild(tr);
      });
    } catch (_) {}
  }

  logEvent(msg) {
    const p = document.createElement('p');
    p.style.padding = '4px 0';
    p.style.borderBottom = '1px solid var(--border)';
    p.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
    this.transcriptLog.prepend(p);
  }

  encodeWav(samples, sampleRate) {
    const buffer = new ArrayBuffer(44 + samples.length * 2);
    const view = new DataView(buffer);

    const writeString = (view, offset, string) => {
      for (let i = 0; i < string.length; i++) {
        view.setUint8(offset + i, string.charCodeAt(i));
      }
    };

    writeString(view, 0, 'RIFF');
    view.setUint32(4, 36 + samples.length * 2, true);
    writeString(view, 8, 'WAVE');
    writeString(view, 12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true); // PCM
    view.setUint16(22, 1, true); // Mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeString(view, 36, 'data');
    view.setUint32(40, samples.length * 2, true);

    for (let i = 0; i < samples.length; i++) {
      view.setInt16(44 + i * 2, samples[i], true);
    }

    return new Blob([view], { type: 'audio/wav' });
  }

  generatePatientQrCode() {
    const patientUrl = `${window.location.origin}/`;
    if (this.qrLink) {
      this.qrLink.textContent = patientUrl;
      this.qrLink.href = patientUrl;
    }

    // Generate high-resolution SVG QR Code directly without external dependencies
    if (this.qrCanvas) {
      const qrApiUrl = `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(patientUrl)}`;
      this.qrCanvas.src = qrApiUrl;
    }
  }
}

document.addEventListener('DOMContentLoaded', () => {
  window.yobaretaNurse = new NurseStationClient();
});
