"""Patient Queue Session Hub for Yobareta PWA.

Maintains active waiting-room patient sessions, routes real-time desk announcements
via WebSockets/SSE to patient devices, and maintains zero-PII in-RAM queue state.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import json
import logging
import time
from typing import Dict, List, Optional, Tuple
import uuid

from fastapi import WebSocket
from matcher import MatchResult, derive_given_name_readings, evaluate_transcript, normalize_reading_morae

logger = logging.getLogger("yobareta.hub")


@dataclass
class PatientSession:
    session_id: str
    name: str
    ticket_number: str = ""
    full_name_readings: List[str] = field(default_factory=list)
    surname_readings: List[str] = field(default_factory=list)
    given_name_readings: List[str] = field(default_factory=list)
    venue_mode: str = "auto"
    status: str = "waiting"  # "waiting" | "called" | "recall" | "dismissed"
    checkin_time: float = field(default_factory=time.time)
    last_called_time: Optional[float] = None
    destination: str = ""
    instruction: str = ""
    matched_trigger: str = ""
    websocket: Optional[WebSocket] = None

    def to_summary(self) -> dict:
        return {
            "session_id": self.session_id,
            "name": self.name,
            "ticket_number": self.ticket_number,
            "status": self.status,
            "destination": self.destination,
            "instruction": self.instruction,
            "checkin_time": int(self.checkin_time),
            "last_called_time": int(self.last_called_time) if self.last_called_time else None,
            "wait_minutes": max(0, int((time.time() - self.checkin_time) / 60)),
        }


class PatientQueueHub:
    """Singleton in-RAM patient queue manager."""

    def __init__(self, session_ttl_seconds: float = 7200.0) -> None:
        self.session_ttl_seconds = session_ttl_seconds
        self._patients: Dict[str, PatientSession] = {}
        self._lock = asyncio.Lock()

    async def register_patient(
        self,
        name: str,
        ticket_number: str = "",
        full_name_readings: Optional[List[str]] = None,
        surname_readings: Optional[List[str]] = None,
        given_name_readings: Optional[List[str]] = None,
        venue_mode: str = "auto",
        session_id: Optional[str] = None,
    ) -> PatientSession:
        async with self._lock:
            sid = session_id or str(uuid.uuid4())[:8]
            full_reads = list(full_name_readings or [])
            sur_reads = list(surname_readings or [])
            giv_reads = list(given_name_readings or [])

            # Automatically derive readings if not passed
            if not full_reads and name:
                full_reads.append(name.strip())
            if not giv_reads and full_reads and sur_reads:
                giv_reads = derive_given_name_readings(full_reads, sur_reads, name)

            session = PatientSession(
                session_id=sid,
                name=name.strip(),
                ticket_number=ticket_number.strip(),
                full_name_readings=full_reads,
                surname_readings=sur_reads,
                given_name_readings=giv_reads,
                venue_mode=venue_mode,
            )
            self._patients[sid] = session
            logger.info("Patient registered in queue: session=%s (ticket=%s)", sid, ticket_number)
            return session

    async def attach_websocket(self, session_id: str, websocket: WebSocket) -> Optional[PatientSession]:
        async with self._lock:
            session = self._patients.get(session_id)
            if session:
                session.websocket = websocket
            return session

    async def detach_websocket(self, session_id: str) -> None:
        async with self._lock:
            session = self._patients.get(session_id)
            if session:
                session.websocket = None

    async def get_patient(self, session_id: str) -> Optional[PatientSession]:
        return self._patients.get(session_id)

    async def get_active_queue(self) -> List[dict]:
        now = time.time()
        # Clean expired
        expired = [sid for sid, p in self._patients.items() if now - p.checkin_time > self.session_ttl_seconds]
        for sid in expired:
            self._patients.pop(sid, None)

        return [p.to_summary() for p in sorted(self._patients.values(), key=lambda x: x.checkin_time)]

    async def remove_patient(self, session_id: str) -> bool:
        async with self._lock:
            if session_id in self._patients:
                self._patients.pop(session_id, None)
                return True
            return False

    async def dismiss_patient(self, session_id: str) -> bool:
        async with self._lock:
            session = self._patients.get(session_id)
            if session:
                session.status = "dismissed"
                return True
            return False

    async def match_announcement(
        self,
        transcript: str,
    ) -> List[Tuple[PatientSession, MatchResult]]:
        """Evaluates a nurse announcement against all active waiting patients."""
        if not transcript.strip():
            return []

        matched_patients: List[Tuple[PatientSession, MatchResult]] = []

        # Make copy of patients to evaluate without holding lock
        active_list = [p for p in self._patients.values() if p.status in ("waiting", "called", "recall")]

        for patient in active_list:
            was_previously_called = patient.status in ("called", "recall")

            res = evaluate_transcript(
                transcript=transcript,
                full_name_readings=patient.full_name_readings,
                surname_readings=patient.surname_readings,
                given_name_readings=patient.given_name_readings,
                ticket_number=patient.ticket_number,
                kanji_name=patient.name,
                previously_called=was_previously_called,
                venue_mode=patient.venue_mode,
            )

            if res.tier in ("called", "recall", "possible"):
                matched_patients.append((patient, res))

        return matched_patients

    async def notify_and_update(
        self,
        patient: PatientSession,
        match: MatchResult,
    ) -> None:
        """Updates patient state and delivers alert payload over WebSocket."""
        async with self._lock:
            patient.status = match.tier
            patient.last_called_time = time.time()
            patient.destination = match.destination
            patient.instruction = match.instruction_plain
            patient.matched_trigger = match.matched_trigger

        alert_payload = {
            "type": "alert",
            "tier": match.tier,
            "score": match.score,
            "matched_trigger": match.matched_trigger,
            "destination": match.destination,
            "instruction_plain": match.instruction_plain,
            "transcript_masked": match.transcript_masked,
            "timestamp": int(time.time()),
        }

        if patient.websocket:
            try:
                await patient.websocket.send_text(json.dumps(alert_payload))
                logger.info("Sent %s alert to session %s over WebSocket", match.tier, patient.session_id)
            except Exception as exc:
                logger.warning("Failed sending WebSocket alert to session %s: %s", patient.session_id, exc)


_GLOBAL_HUB: Optional[PatientQueueHub] = None


def get_patient_hub() -> PatientQueueHub:
    global _GLOBAL_HUB
    if _GLOBAL_HUB is None:
        _GLOBAL_HUB = PatientQueueHub()
    return _GLOBAL_HUB
