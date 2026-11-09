"""Yobareta Cloud Run Backend (asia-northeast1, stateless, zero-PII logging).

Endpoints:
- GET /warm, POST /warm: Pre-warms SudachiPy dictionary, STT V2 client, Agent Platform Gemini client,
  and Firebase Admin SDK when the user taps Start on their phone.
- POST /recognize: Verifies Firebase Auth ID token + App Check token, transcribes 16 kHz mono LINEAR16
  audio via Speech-to-Text V2 (chirp_3 in 'us', supporting ja-JP and en-US), runs the deterministic
  SudachiPy + English phonetic reading matcher, and verifies candidate matches with Gemini Flash
  on Agent Platform (2.5 s timeout).
- GET /healthz: Liveness check.

Privacy guarantee:
- NEVER logs audio or transcripts (which may contain other patients' names in waiting rooms).
- Logs only latency and scores.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import time
from typing import Dict, List, Optional

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile, WebSocket, WebSocketDisconnect, status
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from auth_verify import ensure_firebase_initialized, verify_request_tokens
from matcher import evaluate_transcript, get_reading_extractor
from patient_hub import get_patient_hub, PatientSession
from stage2_gemini import get_stage2_verifier, merge_stage1_and_stage2
from stt_client import get_stt_recognizer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("yobareta.api")

app = FastAPI(
    title="Yobareta Cloud Run API",
    version="1.1.0",
    docs_url=None,
    redoc_url=None,
)

# Ephemeral in-RAM recall tracker: maps anonymized UID hash -> last 'called' monotonic timestamp.
# Never stores names, transcripts, or audio.
_RECALL_WINDOW_SECONDS = 300.0
_RECENT_CALL_TIMESTAMPS: Dict[str, float] = {}

# Sliding-window rate limiter to protect Cloud STT / Gemini credits against abuse
_RATE_WINDOW_SECONDS = 60.0
_MAX_RECOGNIZE_PER_MINUTE_GLOBAL = int(os.environ.get("MAX_RPM_GLOBAL", "60"))
_MAX_RECOGNIZE_PER_MINUTE_PER_CLIENT = int(os.environ.get("MAX_RPM_PER_CLIENT", "30"))
_GLOBAL_REQUEST_TIMESTAMPS: List[float] = []
_CLIENT_REQUEST_TIMESTAMPS: Dict[str, List[float]] = {}


def _enforce_rate_limit(client_key: str) -> None:
    """Blocks requests exceeding per-client or global RPM before any paid Cloud API is called."""
    now = time.monotonic()
    cutoff = now - _RATE_WINDOW_SECONDS

    # Prune global window
    while _GLOBAL_REQUEST_TIMESTAMPS and _GLOBAL_REQUEST_TIMESTAMPS[0] < cutoff:
        _GLOBAL_REQUEST_TIMESTAMPS.pop(0)
    if len(_GLOBAL_REQUEST_TIMESTAMPS) >= _MAX_RECOGNIZE_PER_MINUTE_GLOBAL:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Global rate limit exceeded; please wait a moment.",
        )

    # Prune client window
    client_list = _CLIENT_REQUEST_TIMESTAMPS.get(client_key, [])
    client_list = [ts for ts in client_list if ts >= cutoff]
    if len(client_list) >= _MAX_RECOGNIZE_PER_MINUTE_PER_CLIENT:
        _CLIENT_REQUEST_TIMESTAMPS[client_key] = client_list
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Client rate limit exceeded; please wait a moment.",
        )

    _GLOBAL_REQUEST_TIMESTAMPS.append(now)
    client_list.append(now)
    _CLIENT_REQUEST_TIMESTAMPS[client_key] = client_list


class RecognizeJsonRequest(BaseModel):
    audio_b64: Optional[str] = Field(
        default=None,
        description="Base64-encoded 16 kHz mono 16-bit PCM (LINEAR16) or WAV audio segment.",
    )
    full_name_readings: List[str] = Field(
        default_factory=list,
        description="Registered katakana or English readings for the user's full name.",
    )
    surname_readings: List[str] = Field(
        default_factory=list,
        description="Registered katakana or English readings for the user's surname only.",
    )
    given_name_readings: List[str] = Field(
        default_factory=list,
        description="Registered katakana or English readings for the user's given/first name.",
    )
    kanji_name: Optional[str] = Field(
        default=None,
        description="User's display/kanji/English name (e.g. '東海林太郎' or 'Alex Smith').",
    )
    ticket_number: Optional[str] = Field(
        default=None,
        description="Optional waiting/order ticket number (e.g. '305' or 'A-12').",
    )
    venue_mode: str = Field(
        default="auto",
        description="Venue setting: 'auto' (all venues) | 'hospital' | 'restaurant' | 'cafe'.",
    )
    language_mode: str = Field(
        default="auto",
        description="Language setting: 'auto' (ja-JP + en-US) | 'ja-JP' | 'en-US'.",
    )
    previously_called: bool = Field(
        default=False,
        description="True if the phone already triggered a 'called' alert recently for this session.",
    )
    full_name_threshold: float = Field(default=0.85, ge=0.5, le=1.0)
    surname_threshold: float = Field(default=0.90, ge=0.5, le=1.0)


class PatientCheckinRequest(BaseModel):
    session_id: Optional[str] = Field(default=None, description="Optional pre-assigned session ID from nurse tablet")
    name: str = Field(description="Patient display/kanji/English name")
    ticket_number: str = Field(default="", description="Waiting ticket or order number")
    destination: str = Field(default="", description="Assigned destination room/counter")
    full_name_readings: List[str] = Field(default_factory=list)
    surname_readings: List[str] = Field(default_factory=list)
    given_name_readings: List[str] = Field(default_factory=list)
    venue_mode: str = Field(default="auto")


class NurseSimulateRequest(BaseModel):
    transcript: str = Field(description="Simulated nurse announcement text")


class RecognizeResponse(BaseModel):
    tier: str  # "none" | "possible" | "called" | "recall"
    score: float
    matched_trigger: str
    transcript_masked: str
    destination: str
    instruction_plain: str
    latency_ms: int


def _check_and_update_recall_state(uid_hash: str, client_previously_called: bool) -> bool:
    """Checks if this anonymized user was recently called within _RECALL_WINDOW_SECONDS."""
    now = time.monotonic()
    expired = [k for k, ts in _RECENT_CALL_TIMESTAMPS.items() if now - ts > _RECALL_WINDOW_SECONDS]
    for k in expired:
        _RECENT_CALL_TIMESTAMPS.pop(k, None)

    was_called_recently = client_previously_called or (uid_hash in _RECENT_CALL_TIMESTAMPS)
    return was_called_recently


def _record_call_event(uid_hash: str) -> None:
    _RECENT_CALL_TIMESTAMPS[uid_hash] = time.monotonic()


@app.get("/healthz")
async def healthz() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/warm")
@app.post("/warm")
async def warm(
    uid_hash: str = Depends(verify_request_tokens),
) -> Dict[str, object]:
    """Pre-warms SudachiPy dictionary, STT V2 client, Gemini client, and Firebase Admin."""
    t0 = time.perf_counter()
    ensure_firebase_initialized()
    extractor = get_reading_extractor()
    stt = get_stt_recognizer()
    _ = stt.get_client()
    stage2 = get_stage2_verifier()
    _ = stage2.get_client()
    _ = extractor.tokenize_with_readings("診察室へお入りください")
    warm_ms = int((time.perf_counter() - t0) * 1000)
    logger.info(
        json.dumps(
            {
                "event": "warm",
                "sudachi_ready": extractor.is_ready,
                "stt_location": stt.stt_location,
                "warm_ms": warm_ms,
            }
        )
    )
    return {
        "status": "warm",
        "sudachi_ready": extractor.is_ready,
        "stt_location": stt.stt_location,
        "warm_ms": warm_ms,
    }


async def _parse_recognize_payload(
    request: Request,
    audio_file: Optional[UploadFile],
    metadata_json: Optional[str],
) -> tuple[bytes, RecognizeJsonRequest]:
    """Supports both application/json and multipart/form-data uploads."""
    content_type = (request.headers.get("content-type") or "").lower()

    if "multipart/form-data" in content_type:
        if audio_file is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Missing 'audio' file part in multipart/form-data request",
            )
        audio_bytes = await audio_file.read()
        meta_dict = json.loads(metadata_json or "{}")
        req_model = RecognizeJsonRequest(**meta_dict)
        return audio_bytes, req_model

    try:
        body = await request.json()
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid JSON payload",
        ) from exc

    req_model = RecognizeJsonRequest(**body)
    if not req_model.audio_b64:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing 'audio_b64' in JSON request",
        )
    try:
        audio_bytes = base64.b64decode(req_model.audio_b64)
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid base64 in 'audio_b64'",
        ) from exc

    return audio_bytes, req_model


@app.post("/recognize", response_model=RecognizeResponse)
async def recognize(
    request: Request,
    audio: Optional[UploadFile] = File(default=None),
    metadata: Optional[str] = Form(default=None),
    uid_hash: str = Depends(verify_request_tokens),
) -> RecognizeResponse:
    """Transcribes audio segment with chirp_3, matches readings (JA+EN), and verifies with Gemini Flash."""
    t_start = time.perf_counter()
    client_ip = (request.headers.get("x-forwarded-for") or (request.client.host if request.client else "unknown")).split(",")[0].strip()
    _enforce_rate_limit(f"{uid_hash}:{client_ip}")

    audio_bytes, params = await _parse_recognize_payload(request, audio, metadata)
    if not audio_bytes:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Empty audio payload",
        )

    if len(audio_bytes) > 1_048_576:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="Audio segment exceeds maximum allowed size (1 MB)",
        )

    # 1. Speech-to-Text V2 (chirp_3 in us multi-region, supporting ja-JP and en-US)
    t_stt0 = time.perf_counter()
    stt = get_stt_recognizer()
    try:
        transcript = await asyncio.to_thread(
            stt.recognize_pcm16,
            audio_bytes,
            params.full_name_readings,
            params.surname_readings,
            params.kanji_name,
            params.ticket_number,
            params.venue_mode,
            params.language_mode,
            params.given_name_readings,
        )
    except Exception as exc:
        logger.error(
            json.dumps(
                {
                    "event": "stt_error",
                    "error_type": type(exc).__name__,
                }
            )
        )
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Speech-to-Text recognition failed",
        ) from exc
    stt_ms = int((time.perf_counter() - t_stt0) * 1000)

    # 2. Stage 1: Deterministic SudachiPy (Japanese) + Phonetic (English) matcher
    t_match0 = time.perf_counter()
    # Use client's explicit previously_called state (and server recall state only when authenticated with a unique UID)
    recall_key = f"{uid_hash}:{(params.kanji_name or '').strip()}:{(params.ticket_number or '').strip()}"
    was_called_recently = params.previously_called or (
        os.environ.get("REQUIRE_AUTH", "true").lower() in ("1", "true", "yes")
        and _check_and_update_recall_state(recall_key, False)
    )

    full_thresh = float(os.environ.get("FULL_NAME_THRESHOLD", str(params.full_name_threshold)))
    surname_thresh = float(os.environ.get("SURNAME_THRESHOLD", str(params.surname_threshold)))

    stage1 = evaluate_transcript(
        transcript=transcript,
        full_name_readings=params.full_name_readings,
        surname_readings=params.surname_readings,
        ticket_number=params.ticket_number,
        kanji_name=params.kanji_name,
        previously_called=was_called_recently,
        full_name_threshold=full_thresh,
        surname_threshold=surname_thresh,
        given_name_readings=params.given_name_readings,
        venue_mode=params.venue_mode,
    )
    matcher_ms = int((time.perf_counter() - t_match0) * 1000)

    # 3. Stage 2: Gemini Flash on Agent Platform (only on candidate matches, 2.5s timeout)
    stage2_used = False
    stage2_timed_out = False
    stage2_ms = 0
    final_result = stage1

    if stage1.candidate_for_stage2 and transcript:
        stage2_used = True
        t_s2 = time.perf_counter()
        verifier = get_stage2_verifier()
        s2_res = await verifier.verify_candidate(
            transcript=transcript,
            full_name_readings=params.full_name_readings,
            surname_readings=params.surname_readings,
            kanji_name=params.kanji_name,
            ticket_number=params.ticket_number,
            venue_mode=params.venue_mode,
            stage1=stage1,
            given_name_readings=params.given_name_readings,
        )
        stage2_ms = int((time.perf_counter() - t_s2) * 1000)
        stage2_timed_out = s2_res.timed_out
        final_result = merge_stage1_and_stage2(
            stage1=stage1,
            stage2=s2_res,
            previously_called=was_called_recently,
        )

    if final_result.tier in ("called", "recall"):
        _record_call_event(recall_key)

    total_latency_ms = int((time.perf_counter() - t_start) * 1000)

    # Privacy-first logging: ONLY log latency and scores, NEVER audio or transcripts!
    logger.info(
        json.dumps(
            {
                "event": "recognize",
                "tier": final_result.tier,
                "match_type": final_result.match_type,
                "score": final_result.score,
                "stage2_used": stage2_used,
                "stage2_timed_out": stage2_timed_out,
                "stt_ms": stt_ms,
                "matcher_ms": matcher_ms,
                "stage2_ms": stage2_ms,
                "latency_ms": total_latency_ms,
            }
        )
    )

    return RecognizeResponse(
        tier=final_result.tier,
        score=final_result.score,
        matched_trigger=final_result.matched_trigger,
        transcript_masked=final_result.transcript_masked,
        destination=final_result.destination,
        instruction_plain=final_result.instruction_plain,
        latency_ms=total_latency_ms,
    )

# ==============================================================================
# Patient Queue & Nurse Desk Real-time PWA Endpoints
# ==============================================================================

@app.post("/api/patient/checkin")
async def patient_checkin(req: PatientCheckinRequest) -> dict:
    """Registers a patient who scanned the QR code into the live queue."""
    hub = get_patient_hub()
    session = await hub.register_patient(
        name=req.name,
        ticket_number=req.ticket_number,
        full_name_readings=req.full_name_readings,
        surname_readings=req.surname_readings,
        given_name_readings=req.given_name_readings,
        venue_mode=req.venue_mode,
        session_id=req.session_id,
        destination=req.destination,
    )
    return session.to_summary()


@app.get("/api/patient/{session_id}")
async def get_patient_status(session_id: str) -> dict:
    """Returns current wait status and call destination for a patient."""
    hub = get_patient_hub()
    session = await hub.get_patient(session_id)
    if not session:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    return session.to_summary()


@app.delete("/api/patient/{session_id}")
async def leave_queue(session_id: str) -> dict:
    """Removes a patient from the queue when they leave or check out."""
    hub = get_patient_hub()
    removed = await hub.remove_patient(session_id)
    return {"status": "ok", "removed": removed}


@app.get("/api/queue")
async def get_active_queue() -> List[dict]:
    """Returns the live active queue for the nurse station dashboard."""
    hub = get_patient_hub()
    return await hub.get_active_queue()


@app.websocket("/ws/patient/{session_id}")
async def patient_websocket(websocket: WebSocket, session_id: str):
    """Real-time push channel to deliver vibrations and alerts to patient mobile PWA."""
    hub = get_patient_hub()
    await websocket.accept()
    session = await hub.attach_websocket(session_id, websocket)
    if not session:
        await websocket.close(code=1008, reason="Session not found")
        return

    logger.info("WebSocket connected for patient session: %s", session_id)
    try:
        while True:
            msg = await websocket.receive_text()
            if msg == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        logger.info("WebSocket disconnected for session: %s", session_id)
    except Exception as exc:
        logger.warning("WebSocket error for session %s: %s", session_id, exc)
    finally:
        await hub.detach_websocket(session_id)


@app.post("/api/nurse/simulate")
async def nurse_simulate(req: NurseSimulateRequest) -> dict:
    """Simulates an announcement text, matching against all waiting patients."""
    hub = get_patient_hub()
    matches = await hub.match_announcement(req.transcript)
    matched_summaries = []
    for patient, match in matches:
        await hub.notify_and_update(patient, match)
        matched_summaries.append({
            "session_id": patient.session_id,
            "name": patient.name,
            "ticket": patient.ticket_number,
            "tier": match.tier,
            "destination": match.destination,
            "instruction": match.instruction_plain,
        })
    return {
        "transcript": req.transcript,
        "matches_count": len(matches),
        "matches": matched_summaries,
    }


@app.post("/api/nurse/announce")
async def nurse_announce(
    request: Request,
    audio: Optional[UploadFile] = File(default=None),
) -> dict:
    """Accepts spoken announcement from nurse desk mic, transcribes with STT, and notifies matched patients."""
    audio_bytes = b""
    content_type = (request.headers.get("content-type") or "").lower()
    if "multipart/form-data" in content_type:
        if audio is not None:
            audio_bytes = await audio.read()
    else:
        try:
            body = await request.json()
            if "audio_b64" in body:
                audio_bytes = base64.b64decode(body["audio_b64"])
        except Exception:
            pass

    if not audio_bytes:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Missing audio payload")

    # Transcribe via Speech-to-Text V2 chirp_3
    stt = get_stt_recognizer()
    transcript = await asyncio.to_thread(
        stt.recognize_pcm16,
        audio_bytes,
        [], [], None, None, "auto", "auto"
    )

    hub = get_patient_hub()
    matches = await hub.match_announcement(transcript)
    matched_summaries = []
    for patient, match in matches:
        await hub.notify_and_update(patient, match)
        matched_summaries.append({
            "session_id": patient.session_id,
            "name": patient.name,
            "ticket": patient.ticket_number,
            "tier": match.tier,
            "destination": match.destination,
            "instruction": match.instruction_plain,
        })

    return {
        "transcript": transcript,
        "matches_count": len(matches),
        "matches": matched_summaries,
    }


# Mount static files for PWA (index.html, nurse.html, manifest.json, sw.js, css, js)
_STATIC_DIR = os.path.join(os.path.dirname(__file__), "static")
if os.path.exists(_STATIC_DIR):
    app.mount("/", StaticFiles(directory=_STATIC_DIR, html=True), name="static")
