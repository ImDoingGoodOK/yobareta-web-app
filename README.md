# Yobareta (呼ばれた) — Live Queue Audio Assistance System

> **Never miss your turn.**  
> An assistive waiting-room call detector for Deaf, hard-of-hearing, and international individuals.  
> Built for **DevFest Hackathon 2026** (Team `df-2026-t044`).

---

## 📌 Executive Overview

Waiting rooms in hospitals, outpatient clinics, municipal ward offices (区役所・市役所), immigration centers, and crowded dining establishments are high-friction environments. For Deaf, hard-of-hearing, and elderly individuals (affecting **33.6% of seniors aged 75+ in Japan**, 84.4% of whom do not use hearing aids), anxiety over missing a scratchy PA call forces them to remain tethered to uncomfortable waiting chairs for hours.

**Yobareta** solves this bottleneck through a **Desk-Mic + Patient QR Code Progressive Web App (PWA)** architecture:
1. **Dedicated Nurse-Station Microphone (`/nurse.html`):** A single stationary boundary USB microphone at the reception or nurse's desk captures spoken call announcements (e.g. `「〇〇様、3番診察室へお入りください」` or `"Mr. Alex Smith, please proceed to Exam Room 3"`).
2. **Zero-Friction Patient PWA (`/index.html`):** Patients simply scan a QR code at check-in with any smartphone (iOS Safari / Android Chrome). **No app store download, no login, and zero battery drain.**
3. **Zero Patient Device Audio Processing:** Patients need not grant microphone permissions. Announcements are transcribed centrally via Google Cloud Speech-to-Text V2 (`chirp_3`) and evaluated with our deterministic mora-matcher + Vertex AI Gemini 2.5 Flash disambiguation engine.
4. **Instant Multi-Tier Haptic & Visual Alerts:** The patient’s device receives instant WebSocket push alerts, triggering glanceable haptic vibration (`navigator.vibrate`), dual-tone hospital chime audio, and high-visibility visual directions:
   > **`You were called → Exam Room 3`**
5. **Freedom of Movement:** Patients can freely wait in hospital cafeterias, outdoor gardens, or their personal vehicle without fear of missing their turn.

---

## 🏛️ System Architecture

```
                      ┌────────────────────────────────────────┐
                      │     Nurse Station / Reception Desk     │
                      │  - Stationary USB Boundary Microphone  │
                      │  - Web Audio VAD Slicer (/nurse.html)  │
                      │  - Countertop Check-in QR Code Display │
                      └──────────────────┬─────────────────────┘
                                         │ 2–8s Audio Segment
                                         ▼
                      ┌────────────────────────────────────────┐
                      │    Cloud Run Backend (asia-northeast1) │
                      │  - FastAPI + WebSocket Session Hub     │
                      │  - Cloud STT V2 chirp_3 (us-speech)    │
                      │  - SudachiPy Mora & Phonetic Matcher   │
                      │  - Vertex AI Gemini 2.5 Flash          │
                      └──────────────────┬─────────────────────┘
                                         │ WebSocket / Web Push
                                         ▼
                      ┌────────────────────────────────────────┐
                      │       Patient Device (PWA - /)         │
                      │  - Scanned via Check-in QR Code        │
                      │  - Zero Mic / Zero Audio Processing    │
                      │  - Haptics: navigator.vibrate()        │
                      │  - Dual-Tone Chime (880 Hz → 587 Hz)   │
                      │  - Fullscreen Glanceable Banner Alert  │
                      └────────────────────────────────────────┘
```

---

## 💼 B2B Business Model & Unit Economics

By decoupling speech recognition to a single desk microphone per venue, **cloud AI costs scale with the number of announcements rather than the number of waiting patients**.

- **Cost per 3-second speech clip:** ~¥0.13 to ¥0.50 JPY
- **Monthly Cloud COGS (Multi-Doctor Clinic):** ~¥468 / month
- **Gross Profit Margins:** **65% to 91%**

### Tiered B2B SaaS Monthly Pricing

| Venue Tier | Target Market | Monthly Fee (JPY) | Cloud COGS (Est.) | Gross Margin |
| :--- | :--- | :--- | :--- | :--- |
| **Tier 1: Solo Clinic** | Dentists, dermatology, solo pediatrics (~30 pts/day) | **¥1,500 / mo** | ~¥137 | ~91% |
| **Tier 2: Multi-Doctor Clinic** | Outpatient clinics, internal medicine (~100 pts/day) | **¥5,000 / mo** | ~¥468 | ~90% |
| **Tier 3: Regional Hospital** | General hospitals, university medical centers (~600 pts/day) | **¥30,000 / mo** | ~¥2,730 | ~91% |
| **Public: Ward Office (区役所)** | Municipal registration, health insurance counters | **¥16,000 / mo** | ~¥1,872 | ~88% |
| **Hospitality: Dining / Café** | High-turnover casual restaurants & station counter cafés | **¥500 – ¥2,500 / mo** | ~¥117 – ¥936 | ~63% – 77% |

👉 *For the complete pricing schedule, market research on queue times in Japan, and economic cost analysis, see [**`docs/BUSINESS_MODEL.md`**](docs/BUSINESS_MODEL.md).*

---

## ⚖️ Legal & Regulatory Compliance (Japan)

- **PMD Act Safe (薬機法非該当):** Yobareta is an administrative queue notification and communication assistance system. It performs no clinical diagnosis, treatment planning, or patient monitoring, qualifying for full exemption from Program Medical Device (SaMD) certification.
- **APPI (個人情報保護法):** Raw audio and unmasked transcripts are processed ephemerally in RAM and **never logged or written to disk**. Bystander names in background transcripts are automatically masked (e.g. `[***]様`).
- **Copyright & IP Usage:** All open-source components, models, and synthetic evaluation audio assets conform to their respective licenses. See [**`docs/COPYRIGHT_DECLARATION.md`**](docs/COPYRIGHT_DECLARATION.md).

---

## 📂 Repository Structure

```
.
├── backend/                        # Stateless Python FastAPI Cloud Run Backend
│   ├── static/                     # PWA Web Client & Nurse Station Console
│   │   ├── index.html              # Mobile PWA Check-In & Real-time Alert Screen
│   │   ├── nurse.html              # Nurse Station Web Mic, Audio VAD & Queue Dashboard
│   │   ├── manifest.json           # PWA Manifest (Add to Home Screen)
│   │   ├── sw.js                   # Service Worker (Offline Cache & Web Push)
│   │   ├── css/style.css           # High-Contrast Accessible Stylesheet
│   │   └── js/
│   │       ├── patient.js          # WebSocket client, haptics & audio chime
│   │       └── nurse.js            # Web Audio API VAD, WAV encoder & QR generator
│   ├── patient_hub.py              # In-RAM real-time patient queue & WebSocket dispatcher
│   ├── main.py                     # API routes (/api/patient, /api/nurse, /recognize)
│   ├── matcher.py                  # SudachiPy Japanese mora & Latin phonetic matcher
│   ├── stage2_gemini.py            # Vertex AI Gemini 2.5 Flash disambiguation engine
│   ├── stt_client.py               # Cloud Speech-to-Text V2 chirp_3 client
│   └── tests/                      # Automated unit tests for name & ticket matching
├── docs/
│   ├── BUSINESS_MODEL.md           # B2B Tiered SaaS Pricing & Japan Queue Market Research
│   └── COPYRIGHT_DECLARATION.md    # Formal Copyright & IP Usage Declaration Form
├── eval/                           # Evaluation & Benchmark Suite
│   ├── Yobareta_Eval_Colab.ipynb   # Interactive Google Colab Evaluation Notebook
│   ├── calls.csv                   # 26 benchmark waiting-room audio test cases
│   ├── generate_audio.py           # Synthetic audio clip generator (gTTS + ffmpeg)
│   ├── score.py                    # Benchmark scoring & metrics evaluator
│   └── test_audio_e2e.py           # End-to-end audio recognition test harness
└── app/                            # Optional Native Android Client (Kotlin + Jetpack Compose)
```

---

## 🚀 Quickstart & Testing

### 1. Test the Web Application Locally

```bash
cd backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8080 --reload
```

1. Open **Patient Check-in PWA:** `http://localhost:8080/`
2. Open **Nurse Station Console:** `http://localhost:8080/nurse.html`
3. On the Nurse Console, click **Call / 呼出** or speak into the microphone:
   > `「東海林太郎様、3番診察室へお入りください」`
4. The patient's browser vibrates, plays a dual-tone hospital chime, and flashes:
   > **`You were called → Exam Room 3`**

### 2. Run the Benchmark Evaluation

```bash
# Verify the deterministic matcher against all 26 benchmark test cases:
python3 eval/score.py --verify-matcher
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
