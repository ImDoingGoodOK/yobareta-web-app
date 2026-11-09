# Yobareta (呼ばれた) — B2B Business Model & Market Research

> **Product:** Yobareta (呼ばれた) — Live Queue Audio Assistance System  
> **Target Deployment:** Google Cloud Run (`asia-northeast1`) & Progressive Web Application (PWA)  
> **Market Focus:** Japan Outpatient Clinics, General Hospitals, Municipal Ward Offices (区役所・市役所), Immigration Bureaus, and Dining  
> **Last Updated:** November 2026  

---

## 1. Executive Summary: The Desk-Mic Architecture Shift

### The Legacy Problem (Per-Phone Listening)
Traditional audio transcription apps (like Google Live Transcribe) require every user to download a native mobile app, grant persistent microphone permissions, and process audio streams on their personal device. In a waiting room with 50 patients, 50 devices are continuously sampling audio, consuming battery, and running into aggressive OS background audio restrictions and screen locks. For software vendors, cloud AI costs scale linearly with the number of waiting users.

### The Yobareta Innovation: Desk-Mic + Patient QR Code PWA
Yobareta decouples audio processing from the patient’s personal phone:
1. **Dedicated Station Microphone:** A single stationary boundary USB microphone placed at the nurse's station, reception window, or kiosk captures spoken queue announcements (e.g. `「〇〇様、3番診察室へお入りください」` or `"Mr. Alex Smith, please proceed to Exam Room 3"`).
2. **Zero-Friction Patient PWA:** Patients simply scan a QR code at check-in (counter sign, ticket receipt, or kiosk display). It launches a lightweight Progressive Web App in iOS Safari or Android Chrome.
3. **Zero Device Audio Processing:** The patient’s device runs no speech recognition and requires no microphone permissions. When the nurse speaks, Cloud Run processes the single announcement, identifies the recipient, and fires an instant WebSocket/Web Push alert with haptic vibration (`navigator.vibrate`) and glanceable directions.
4. **Freedom of Movement:** Patients are untethered from crowded waiting rooms. They can wait in hospital cafeterias, courtyards, cars, or nearby shops without fear of missing their turn.

---

## 2. Unit Economics & Decoupled Scaling

### Decoupled Scaling Economics
In the desk-mic architecture, cloud AI costs (Speech-to-Text V2 + Gemini Flash) are determined **only** by the number of nurse announcements, completely independent of how many patients are in the waiting room.

- **Cost per 3-second speech clip:** ~¥0.13 to ¥0.50 JPY  
  - Cloud Speech-to-Text V2 (`chirp_3`): ~¥0.09 per 3s clip  
  - Vertex AI Gemini 2.5 Flash: ~¥0.04 per disambiguation call  
- **Volume Metric:** Based on roughly 1.2 announcements per patient visit (accounting for call-outs and repeat pages).

---

## 3. Tiered B2B SaaS Pricing Schedule (in Japanese Yen)

Hospitals and clinics have diverse budgets and operating scales. Yobareta uses a tiered B2B monthly subscription with a **40% to 90% gross profit margin**:

### Primary Healthcare Pricing Schedule

| Venue Type | Typical Daily Traffic | Monthly Announcements (Est.) | Monthly Cloud COGS (@ ¥0.13/clip) | Recommended Monthly Fee (JPY) | Gross Profit Margin | Target Segment |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Tier 1: Solo Clinic** | ~30 patients/day | ~1,050 clips | ~¥137 / mo | **¥1,500 / mo** | ~91% | Dentists, dermatology, ophthalmology, solo pediatric offices |
| **Tier 2: Multi-Doctor Clinic** | ~100 patients/day | ~3,600 clips | ~¥468 / mo | **¥5,000 / mo** | ~90% | Multi-specialty outpatient clinics, general practices |
| **Tier 3: Regional General Hospital** | ~600 patients/day | ~21,000 clips | ~¥2,730 / mo | **¥30,000 / mo** | ~91% | Regional hospitals, university medical centers (multi-department) |

### Conservative Stress-Test Pricing (@ ¥0.50 / clip COGS, Strict 40% Target Floor)

| Venue Type | Monthly Clips | Monthly Cloud COGS (@ ¥0.50) | Adjusted 40% Margin Floor | Recommended Commercial Tier | Resulting Gross Margin |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Solo Clinic** | ~1,050 clips | ¥525 | ¥875 / mo | **¥900 – ¥1,500 / mo** | 41.7% – 65% |
| **Multi-Doctor Clinic** | ~3,600 clips | ¥1,800 | ¥3,000 / mo | **¥3,000 – ¥5,000 / mo** | 40.0% – 64% |
| **Regional Hospital** | ~21,000 clips | ¥10,500 | ¥17,500 / mo | **¥17,500 – ¥30,000 / mo** | 40.0% – 65% |

### Public Sector & Hospitality Expansion Tiers

| Venue Type | Typical Traffic | Monthly Clips | Cloud COGS (@ ¥0.13) | Recommended Monthly Fee | Net Margin | Value Proposition |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Ward Office (区役所 / 市役所)** | ~400 citizens/day | ~14,400 clips | ~¥1,872 | **¥16,000 / mo** | ~88% | Reduce waiting hall overcrowding for residency, tax, and health insurance counters |
| **Immigration Bureau (出入国在留管理局)** | ~500 visitors/day | ~18,000 clips | ~¥2,340 | **¥25,000 / mo** | ~90% | Multilingual call announcements for international visa applicants |
| **Casual Eatery / Ramen Shop** | ~25 groups/day | ~900 clips | ~¥117 | **¥500 / mo** | ~76% | Eliminate entryway line crowding; prevent party walk-outs |
| **Busy Conveyor Sushi / Dining** | ~80 groups/day | ~2,880 clips | ~¥374 | **¥1,600 / mo** | ~77% | Diners browse nearby shopping mall while holding digital place |
| **Busy Station Counter Café** | ~200 orders/day | ~7,200 clips | ~¥936 | **¥2,500 / mo** | ~63% | Numbered ticket order pickup at high-speed commuter hubs |

---

## 4. Market Research: Queue Times & Economic Cost in Japan

### A. Outpatient Clinics & Hospitals (総合病院・一般クリニック)
- **The Reality:** Japan has one of the highest per-capita physician visit rates in the OECD (averaging **over 12 visits per person annually**). Outpatient waiting rooms frequently experience bottlenecks.
- **Typical Wait Time:** Patients regularly wait **1 to 3 hours** for consultations, followed by additional wait times for tests, pharmacy, and billing.
- **The Pain Point:** Patients are tethered to physical waiting rooms because they fear missing their name or ticket number called over scratchy, echoey PA speakers. Over 33.6% of seniors (aged 75+) suffer from hearing loss, and 84.4% do not wear hearing aids (JapanTrak 2025).

### B. Ward & Municipal Offices (区役所・市役所)
- **The Reality:** Handling mandatory administrative procedures (moving notifications, resident registry certificates `住民票`, national health insurance `国民健康保険`) generates severe foot-traffic surges during peak moving seasons (March–April) and lunch hours.
- **Typical Wait Time:** **30 minutes to over 2 hours** to reach a counter.

### C. Immigration Services Bureau (出入国在留管理局)
- **The Reality:** Major regional centers (Tokyo Shinagawa, Osaka, Nagoya) handle massive queues for visa renewals, status adjustments, and permanent residency.
- **Typical Wait Time:** **2 to 4+ hours** per visit. Language barriers compound anxiety over spoken announcements.

### D. Popular Dining & Cafés (飲食店・人気カフェ)
- **The Reality:** Peak lunch/dinner hours result in **30 to 90 minutes** of physical queuing outside or in cramped entryways.
- **Economic Loss (Walk-Outs / Balking):** When prospective diners see long physical queues, 15%–25% abandon the line, directly reducing restaurant revenue.

### E. Macroeconomic Opportunity Cost
If a salaried worker spends 2 hours waiting at a ward office or clinic during a workday, it translates to lost corporate productivity. Across millions of citizens annually, **hundreds of billions of yen in economic value are lost to idle waiting**. Yobareta enables waiting time to become productive or relaxing personal time.

---

## 5. Legal & Regulatory Compliance (Japan)

### A. Non-Medical Device Status (PMD Act Exemption)
- Under Japan's **Pharmaceuticals and Medical Devices Act (薬機法 - PMD Act)**, software that performs medical diagnosis, treatment planning, or clinical monitoring is classified as a Program Medical Device (SaMD) subject to MHLW certification.
- **Yobareta is strictly an administrative queue notification and communication assistance tool.** It does not analyze patient health data, vital signs, or clinical records, ensuring full exemption from PMD Act medical device certification.

### B. Act on the Protection of Personal Information (APPI - 個人情報保護法)
- **Stateless Cloud Processing:** Cloud Run processes audio segments ephemerally in RAM. Raw audio and raw unmasked transcripts are **never logged, persisted to disk, or stored in databases**.
- **PII Masking:** Before logging operational metrics, bystander names in transcripts are automatically masked (e.g., `[***]様`).
- **Data Minimization:** Patient check-ins only track the active session while waiting. Upon checkout or session expiration, patient records are cleared from RAM.

### C. MHLW Medical Safety & Accessibility Guidelines
- Conforms to Ministry of Health, Labour and Welfare (厚生労働省 - MHLW) medical institution accessibility standards by providing accessible visual, haptic, and text-based assistance for Deaf, hard-of-hearing, and foreign patients.
