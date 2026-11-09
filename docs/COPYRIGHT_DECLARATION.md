================================================================================
 COPYRIGHT & IP USAGE DECLARATION FORM
================================================================================

Project Title: Yobareta (呼ばれた) - Live Queue Audio Assistance System
Team / Creator: DevFest Hackathon 2026 Team df-2026-t044 (GCP Project: 696344028704)
Target Deployment: Google Cloud Run (asia-northeast1) & Web Application (PWA)
Date: November 8, 2026

--------------------------------------------------------------------------------
1. DECLARATION OF ORIGINALITY
--------------------------------------------------------------------------------
The undersigned developers declare that the custom application code, architecture,
Mora matching logic (SudachiPy integration), and client-side PWA software for the
"Yobareta" project are original creations developed for the DevFest GDGoC Japan
Hackathon 2026.

--------------------------------------------------------------------------------
2. THIRD-PARTY LIBRARIES, MODELS & APIS
--------------------------------------------------------------------------------
The project integrates the following external software components, APIs, and open-source
libraries, each adhering to its respective license terms:

Component / Asset Name       | Provider / Source        | License / Terms
-----------------------------+--------------------------+-----------------------
Google Cloud Speech-to-Text  | Google Cloud Platform    | GCP API Terms of Service
(Chirp 3 V2 Engine)          | (us-speech)              | 
-----------------------------+--------------------------+-----------------------
Vertex AI Gemini 2.5 Flash   | Google Cloud Platform    | GCP Enterprise Terms
(Disambiguation Engine)      | (asia-northeast1)        | 
-----------------------------+--------------------------+-----------------------
FastAPI Framework            | Open Source              | MIT License
-----------------------------+--------------------------+-----------------------
SudachiPy (Tokenizer)        | Works Applications       | Apache License 2.0
-----------------------------+--------------------------+-----------------------
gTTS (Google Text-to-Speech) | gTTS Python Package      | MIT License
(Synthetic Benchmark Audio)  | (ffmpeg audio processing)| 
-----------------------------+--------------------------+-----------------------
Firebase Authentication      | Google Firebase          | Firebase Terms of Service
(Anonymous ID Tokens)        |                          | 
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
3. SAMPLE DATA & AUDIO ASSET DECLARATION
--------------------------------------------------------------------------------
- Test Audio Datasets: All 26 benchmark audio clips (A01–N02) used for testing
  and validation were synthetically generated using gTTS and ffmpeg.
- Privacy & PII Compliance: No real human voice recordings, protected personal
  information (PHI), or copyrighted commercial audio tracks were used in training,
  evaluating, or operating the system. All test cases use synthetic names
  (e.g., Shoji Taro, Alex Smith) and generic ticket numbers.

--------------------------------------------------------------------------------
4. INTELLECTUAL PROPERTY & PERMISSION GRANT
--------------------------------------------------------------------------------
The project team retains full intellectual property rights to the "Yobareta"
source code. The team hereby grants DevFest 2026 Organizers and Judges a non-exclusive,
royalty-free, temporary license to run, test, and demonstrate the application via the
provided Interactive Google Colab Evaluation Kit and Cloud Run endpoints.

--------------------------------------------------------------------------------
DECLARANT SIGNATURE(S)
--------------------------------------------------------------------------------
Team Lead / Representative: DevFest Hackathon 2026 Team df-2026-t044
Team Name: DevFest Hackathon 2026 (Team df-2026-t044)
Date: November 8, 2026
================================================================================
