package com.example.yobareta.model

import java.util.UUID

enum class PatientQueueStatus {
    WAITING,
    CALLED,
    RECALL,
    COMPLETED
}

data class DailyPatientEntry(
    val id: String = UUID.randomUUID().toString().take(8),
    val ticketNumber: String,
    val name: String,
    val fullReadings: List<String> = emptyList(),
    val surnameReadings: List<String> = emptyList(),
    val givenReadings: List<String> = emptyList(),
    val destination: String = "診察室 (Exam Room)",
    val checkinTimeMs: Long = System.currentTimeMillis(),
    val lastCalledTimeMs: Long? = null,
    val callCount: Int = 0,
    val status: PatientQueueStatus = PatientQueueStatus.WAITING,
    val notes: String = ""
) {
    val waitMinutes: Int
        get() = ((System.currentTimeMillis() - checkinTimeMs) / 60000L).coerceAtLeast(0).toInt()

    fun toPwaUrl(baseUrl: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
        val encodedTicket = java.net.URLEncoder.encode(ticketNumber, "UTF-8")
        return "$cleanBase/?sid=$id&ticket=$encodedTicket&name=$encodedName"
    }
}

data class NurseAnnounceResult(
    val transcript: String,
    val matchesCount: Int,
    val matchedSummaries: List<MatchedPatientSummary>,
    val latencyMs: Int = 0
)

data class MatchedPatientSummary(
    val sessionId: String,
    val name: String,
    val ticket: String,
    val tier: String,
    val destination: String,
    val instruction: String
)
