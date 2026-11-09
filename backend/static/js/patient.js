// Yobareta Patient PWA Client - Zero-Mic, Zero-Battery Drain Queue Alert

class PatientClient {
  constructor() {
    this.session = null;
    this.ws = null;
    this.reconnectTimer = null;
    this.pollTimer = null;
    this.lastHandledCallCount = 0;
    this.audioCtx = null;
    this.vibrateInterval = null;

    this.initElements();
    this.bindEvents();
    this.initSessionFromUrlOrStorage();
    this.registerServiceWorker();
  }

  initElements() {
    this.checkinCard = document.getElementById('checkinCard');
    this.waitingCard = document.getElementById('waitingCard');
    this.nameInput = document.getElementById('nameInput');
    this.ticketInput = document.getElementById('ticketInput');
    this.venueSelect = document.getElementById('venueSelect');
    this.readingChips = document.getElementById('readingChips');
    this.checkinBtn = document.getElementById('checkinBtn');
    this.leaveBtn = document.getElementById('leaveBtn');
    this.testAlertBtn = document.getElementById('testAlertBtn');

    this.displayTicket = document.getElementById('displayTicket');
    this.displayName = document.getElementById('displayName');
    this.displayTempId = document.getElementById('displayTempId');
    this.statusBadge = document.getElementById('statusBadge');
    this.statusDot = document.getElementById('statusDot');
    this.statusText = document.getElementById('statusText');

    this.alertOverlay = document.getElementById('alertOverlay');
    this.alertTierBadge = document.getElementById('alertTierBadge');
    this.alertDestination = document.getElementById('alertDestination');
    this.alertInstruction = document.getElementById('alertInstruction');
    this.dismissAlertBtn = document.getElementById('dismissAlertBtn');
  }

  bindEvents() {
    this.nameInput.addEventListener('input', () => this.generateReadingChips(this.nameInput.value));
    this.checkinBtn.addEventListener('click', () => {
      this.unlockAudio();
      this.handleCheckin();
    });
    this.leaveBtn.addEventListener('click', () => this.handleLeaveQueue());
    this.testAlertBtn.addEventListener('click', () => {
      this.unlockAudio();
      this.triggerTestAlert();
    });
    this.dismissAlertBtn.addEventListener('click', () => this.dismissAlert());

    // Unlock Web Audio API on first user touch anywhere on the page
    document.body.addEventListener('touchstart', () => this.unlockAudio(), { once: true });
    document.body.addEventListener('click', () => this.unlockAudio(), { once: true });
  }

  unlockAudio() {
    try {
      if (!this.audioCtx) {
        this.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      }
      if (this.audioCtx.state === 'suspended') {
        this.audioCtx.resume();
      }
    } catch (_) {}
  }

  registerServiceWorker() {
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch((err) => {
        console.warn('SW registration failed:', err);
      });
    }
  }

  // Pre-generate readings locally for common multi-reading surnames
  generateReadingChips(name) {
    const trimmed = (name || '').trim();
    if (!trimmed) {
      this.readingChips.innerHTML = '';
      return [];
    }

    const dict = {
      '東海林': ['ショウジ', 'トウカイリン', 'Shoji'],
      '佐藤': ['サトウ', 'Sato'],
      '鈴木': ['スズキ', 'Suzuki'],
      '高橋': ['タカハシ', 'Takahashi'],
      '田中': ['タナカ', 'Tanaka'],
      '渡辺': ['ワタナベ', 'Watanabe'],
      '小鳥遊': ['タカナシ'],
      'Alex': ['アレックス', 'Alex'],
      'Smith': ['スミス', 'Smith'],
      'John': ['ジョン', 'John']
    };

    let chips = [];
    for (const [key, readings] of Object.entries(dict)) {
      if (trimmed.includes(key)) {
        chips.push(...readings);
      }
    }

    if (chips.length === 0) {
      chips = [trimmed];
    }

    this.readingChips.innerHTML = '';
    const unique = [...new Set(chips)];
    unique.forEach((rd) => {
      const chip = document.createElement('span');
      chip.className = 'chip active';
      chip.textContent = rd;
      this.readingChips.appendChild(chip);
    });
    return unique;
  }

  async initSessionFromUrlOrStorage() {
    const params = new URLSearchParams(window.location.search);
    const qrSid = params.get('sid') || '';
    const qrTicket = params.get('ticket') || params.get('t') || '';
    const qrName = params.get('name') || params.get('n') || '';
    const qrDest = params.get('dest') || '';

    // If opened by scanning a Patient QR code from the Nurse Tablet App:
    if (qrSid || qrTicket || qrName) {
      this.nameInput.value = qrName;
      this.ticketInput.value = qrTicket;
      const readings = this.generateReadingChips(qrName);

      try {
        const resp = await fetch('/api/patient/checkin', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            session_id: qrSid || undefined,
            name: qrName || `Ticket #${qrTicket}`,
            ticket_number: qrTicket,
            destination: qrDest || undefined,
            full_name_readings: readings,
            surname_readings: readings.slice(0, 3),
            given_name_readings: readings.slice(0, 2),
            venue_mode: 'auto'
          })
        });

        if (resp.ok) {
          const data = await resp.json();
          this.session = data;
          this.lastHandledCallCount = data.call_count || 0;
          localStorage.setItem('yobareta_patient_session', JSON.stringify(this.session));
          // Clean query params from address bar so refresh stays clean
          window.history.replaceState({}, document.title, window.location.pathname);
          this.showWaitingState();
          this.startLiveMonitoring();
          return;
        }
      } catch (err) {
        console.warn('Auto check-in from QR failed, falling back:', err);
      }
    }

    // Otherwise restore from localStorage if available
    const stored = localStorage.getItem('yobareta_patient_session');
    if (stored) {
      try {
        this.session = JSON.parse(stored);
        this.lastHandledCallCount = this.session.call_count || 0;
        this.showWaitingState();
        this.startLiveMonitoring();
      } catch (_) {
        localStorage.removeItem('yobareta_patient_session');
      }
    }
  }

  async handleCheckin() {
    const name = this.nameInput.value.trim();
    const ticket = this.ticketInput.value.trim();
    const venue = this.venueSelect ? this.venueSelect.value : 'auto';

    if (!name && !ticket) {
      alert('Please enter your Name or Ticket Number / お名前または番号を入力してください');
      return;
    }

    this.checkinBtn.disabled = true;
    this.checkinBtn.textContent = 'Joining Waiting List...';

    const readings = Array.from(this.readingChips.querySelectorAll('.chip')).map((c) => c.textContent);

    try {
      const resp = await fetch('/api/patient/checkin', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name || `Ticket #${ticket}`,
          ticket_number: ticket,
          full_name_readings: readings,
          surname_readings: readings.slice(0, 3),
          given_name_readings: readings.slice(0, 2),
          venue_mode: venue
        })
      });

      if (!resp.ok) {
        throw new Error(`Server returned ${resp.status}`);
      }

      const data = await resp.json();
      this.session = data;
      this.lastHandledCallCount = data.call_count || 0;
      localStorage.setItem('yobareta_patient_session', JSON.stringify(this.session));

      this.showWaitingState();
      this.startLiveMonitoring();
    } catch (err) {
      alert(`Check-in error: ${err.message}`);
    } finally {
      this.checkinBtn.disabled = false;
      this.checkinBtn.textContent = 'Join Waiting List / 受付を開始する';
    }
  }

  showWaitingState() {
    this.checkinCard.classList.add('hidden');
    this.waitingCard.classList.remove('hidden');

    this.displayTicket.textContent = this.session.ticket_number ? `#${this.session.ticket_number}` : '✓';
    this.displayName.textContent = this.session.name;
    if (this.displayTempId) {
      this.displayTempId.textContent = `Temporary ID: ${this.session.session_id} ${this.session.destination ? '• ' + this.session.destination : ''}`;
    }
  }

  startLiveMonitoring() {
    this.connectWebSocket();
    this.startPollingFallback();
  }

  connectWebSocket() {
    if (!this.session) return;
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    try {
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
      const wsUrl = `${protocol}//${window.location.host}/ws/patient/${this.session.session_id}`;

      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        this.setOnlineStatus(true);
        if (this.reconnectTimer) {
          clearInterval(this.reconnectTimer);
          this.reconnectTimer = null;
        }
      };

      this.ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'alert') {
            if (msg.call_count) {
              this.lastHandledCallCount = msg.call_count;
            } else {
              this.lastHandledCallCount += 1;
            }
            this.triggerAlert(msg);
          }
        } catch (e) {
          console.error('Error parsing WS message:', e);
        }
      };

      this.ws.onclose = () => {
        this.scheduleReconnect();
      };

      this.ws.onerror = () => {
        // Polling fallback keeps monitoring active even if WS is unsupported
      };
    } catch (_) {}
  }

  startPollingFallback() {
    if (this.pollTimer) clearInterval(this.pollTimer);
    const poll = async () => {
      if (!this.session) return;
      try {
        const resp = await fetch(`/api/patient/${this.session.session_id}`);
        if (resp.ok) {
          this.setOnlineStatus(true);
          const data = await resp.json();
          if (data.name && data.name !== this.session.name) {
            this.session.name = data.name;
            this.displayName.textContent = data.name;
          }
          if (data.ticket_number && data.ticket_number !== this.session.ticket_number) {
            this.session.ticket_number = data.ticket_number;
            this.displayTicket.textContent = `#${data.ticket_number}`;
          }
          const serverCallCount = data.call_count || 0;
          if (serverCallCount > this.lastHandledCallCount && data.latest_alert) {
            this.lastHandledCallCount = serverCallCount;
            this.triggerAlert(data.latest_alert);
          }
        }
      } catch (_) {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
          this.setOnlineStatus(false);
        }
      }
    };
    poll();
    this.pollTimer = setInterval(poll, 1500);
  }

  scheduleReconnect() {
    if (this.reconnectTimer) return;
    this.reconnectTimer = setInterval(() => {
      if (this.session) {
        this.connectWebSocket();
      }
    }, 5000);
  }

  setOnlineStatus(online) {
    if (online) {
      this.statusBadge.className = 'status-badge connected';
      this.statusText.textContent = 'Connected (Live Monitoring)';
    } else {
      this.statusBadge.className = 'status-badge disconnected';
      this.statusText.textContent = 'Reconnecting...';
    }
  }

  playHospitalChime() {
    try {
      if (!this.audioCtx) {
        this.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      }
      if (this.audioCtx.state === 'suspended') {
        this.audioCtx.resume();
      }

      const now = this.audioCtx.currentTime;
      // Tone 1: High chime (A5 - 880 Hz)
      const osc1 = this.audioCtx.createOscillator();
      const gain1 = this.audioCtx.createGain();
      osc1.frequency.setValueAtTime(880, now);
      gain1.gain.setValueAtTime(0.5, now);
      gain1.gain.exponentialRampToValueAtTime(0.001, now + 0.8);
      osc1.connect(gain1);
      gain1.connect(this.audioCtx.destination);
      osc1.start(now);
      osc1.stop(now + 0.8);

      // Tone 2: Warm second chime (D5 - 587 Hz)
      const osc2 = this.audioCtx.createOscillator();
      const gain2 = this.audioCtx.createGain();
      osc2.frequency.setValueAtTime(587.33, now + 0.4);
      gain2.gain.setValueAtTime(0.6, now + 0.4);
      gain2.gain.exponentialRampToValueAtTime(0.001, now + 1.4);
      osc2.connect(gain2);
      gain2.connect(this.audioCtx.destination);
      osc2.start(now + 0.4);
      osc2.stop(now + 1.4);
    } catch (e) {
      console.warn('Audio chime playback blocked:', e);
    }
  }

  triggerAlert(alertData) {
    const tier = alertData.tier || 'called';
    const dest = alertData.destination || 'Please Proceed';
    const instr = alertData.instruction_plain || `You were called → ${dest}`;

    this.alertOverlay.className = `alert-overlay tier-${tier}`;
    this.alertTierBadge.textContent = tier.toUpperCase();
    this.alertDestination.textContent = dest;
    this.alertInstruction.textContent = instr;
    this.alertOverlay.classList.remove('hidden');

    // Chime & Haptics
    this.playHospitalChime();

    if ('vibrate' in navigator) {
      navigator.vibrate([600, 200, 600, 200, 800, 300, 600]);
      if (this.vibrateInterval) clearInterval(this.vibrateInterval);
      this.vibrateInterval = setInterval(() => {
        navigator.vibrate([600, 200, 600, 200, 800]);
      }, 2500);
    }
  }

  triggerTestAlert() {
    this.triggerAlert({
      tier: 'called',
      destination: 'Exam Room 3 (3番診察室)',
      instruction_plain: 'You were called → Exam Room 3'
    });
  }

  dismissAlert() {
    this.alertOverlay.classList.add('hidden');
    if (this.vibrateInterval) {
      clearInterval(this.vibrateInterval);
      this.vibrateInterval = null;
    }
    if ('vibrate' in navigator) {
      navigator.vibrate(0);
    }
  }

  async handleLeaveQueue() {
    if (!confirm('Leave the waiting list? / 待機をキャンセルしますか？')) return;

    if (this.session) {
      try {
        await fetch(`/api/patient/${this.session.session_id}`, { method: 'DELETE' });
      } catch (_) {}
    }

    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }

    this.dismissAlert();
    this.session = null;
    localStorage.removeItem('yobareta_patient_session');

    this.waitingCard.classList.add('hidden');
    this.checkinCard.classList.remove('hidden');
    this.setOnlineStatus(false);
  }
}

document.addEventListener('DOMContentLoaded', () => {
  window.yobaretaPatient = new PatientClient();
});
