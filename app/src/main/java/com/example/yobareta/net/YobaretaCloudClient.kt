package com.example.yobareta.net

import android.util.Base64
import android.util.Log
import com.example.yobareta.AppConfig
import com.example.yobareta.auth.AuthManager
import com.example.yobareta.model.DailyPatientEntry
import com.example.yobareta.model.MatchedPatientSummary
import com.example.yobareta.model.NurseAnnounceResult
import com.example.yobareta.model.RecognizeAlertResult
import com.example.yobareta.model.UserWatchProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YobaretaCloudClient(
    private val authManager: AuthManager = AuthManager()
) {
    companion object {
        private const val TAG = "YobaretaCloudClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun resolveBaseUrl(overrideUrl: String): String {
        val raw = overrideUrl.trim().ifEmpty { AppConfig.DEFAULT_CLOUD_RUN_BASE_URL }
        return raw.trimEnd('/')
    }

    /**
     * Pings /warm when the user taps Start to eliminate Cloud Run cold starts.
     */
    suspend fun warmUp(baseUrlOverride: String = ""): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(baseUrlOverride)
        try {
            val idToken = authManager.getIdToken()
            val appCheckToken = authManager.getAppCheckToken()

            val reqBuilder = Request.Builder()
                .url("$baseUrl/warm")
                .get()
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("X-Firebase-AppCheck", appCheckToken)
            if (AppConfig.API_SECRET.isNotEmpty()) {
                reqBuilder.addHeader("X-Yobareta-Secret", AppConfig.API_SECRET)
            }
            val request = reqBuilder.build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "/warm returned HTTP ${resp.code}")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "/warm failed: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Uploads a 2–8 s 16 kHz mono LINEAR16 audio segment to POST /recognize.
     */
    suspend fun recognizeSegment(
        pcm16LeBytes: ByteArray,
        profile: UserWatchProfile,
        previouslyCalled: Boolean
    ): RecognizeAlertResult = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(profile.cloudRunBaseUrl)
        val idToken = authManager.getIdToken()
        val appCheckToken = authManager.getAppCheckToken()

        val audioB64 = Base64.encodeToString(pcm16LeBytes, Base64.NO_WRAP)
        val bodyJson = JSONObject().apply {
            put("audio_b64", audioB64)
            put("full_name_readings", JSONArray(profile.allFullNameReadings()))
            put("surname_readings", JSONArray(profile.allSurnameReadings()))
            put("given_name_readings", JSONArray(profile.allGivenNameReadings()))
            put("kanji_name", profile.kanjiName.trim())
            put("ticket_number", profile.ticketNumber.trim())
            put("venue_mode", profile.venueMode)
            put("language_mode", profile.languageMode)
            put("previously_called", previouslyCalled)
        }

        val reqBuilder = Request.Builder()
            .url("$baseUrl/recognize")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("X-Firebase-AppCheck", appCheckToken)
        if (AppConfig.API_SECRET.isNotEmpty()) {
            reqBuilder.addHeader("X-Yobareta-Secret", AppConfig.API_SECRET)
        }
        val request = reqBuilder.build()

        httpClient.newCall(request).execute().use { resp ->
            val rawBody = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                throw IllegalStateException("Cloud Run /recognize HTTP ${resp.code}: $rawBody")
            }
            val json = JSONObject(rawBody)
            RecognizeAlertResult(
                tier = json.optString("tier", "none"),
                score = json.optDouble("score", 0.0).toFloat(),
                matchedTrigger = json.optString("matched_trigger", ""),
                transcriptMasked = json.optString("transcript_masked", ""),
                destination = json.optString("destination", ""),
                instructionPlain = json.optString("instruction_plain", ""),
                latencyMs = json.optInt("latency_ms", 0)
            )
        }
    }

    /**
     * Sends spoken announcement from the nurse station tablet microphone to Cloud Run.
     * Cloud Run transcribes with STT chirp_3 and pushes real-time WebSocket alerts to matched patients.
     */
    suspend fun announceAudio(
        pcm16LeBytes: ByteArray,
        baseUrlOverride: String = ""
    ): NurseAnnounceResult = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(baseUrlOverride)
        val audioB64 = Base64.encodeToString(pcm16LeBytes, Base64.NO_WRAP)
        val bodyJson = JSONObject().apply {
            put("audio_b64", audioB64)
        }

        val reqBuilder = Request.Builder()
            .url("$baseUrl/api/nurse/announce")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (AppConfig.API_SECRET.isNotEmpty()) {
            reqBuilder.addHeader("X-Yobareta-Secret", AppConfig.API_SECRET)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val rawBody = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                throw IllegalStateException("Cloud Run /api/nurse/announce HTTP ${resp.code}: $rawBody")
            }
            val json = JSONObject(rawBody)
            val summaries = mutableListOf<MatchedPatientSummary>()
            val arr = json.optJSONArray("matches")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    summaries.add(
                        MatchedPatientSummary(
                            sessionId = m.optString("session_id", ""),
                            name = m.optString("name", ""),
                            ticket = m.optString("ticket", ""),
                            tier = m.optString("tier", "called"),
                            destination = m.optString("destination", ""),
                            instruction = m.optString("instruction", "")
                        )
                    )
                }
            }
            NurseAnnounceResult(
                transcript = json.optString("transcript", ""),
                matchesCount = json.optInt("matches_count", 0),
                matchedSummaries = summaries
            )
        }
    }

    suspend fun registerPatientInCloud(
        entry: DailyPatientEntry,
        baseUrlOverride: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(baseUrlOverride)
        val bodyJson = JSONObject().apply {
            put("session_id", entry.id)
            put("name", entry.name)
            put("ticket_number", entry.ticketNumber)
            put("destination", entry.destination)
            put("full_name_readings", JSONArray(entry.fullReadings))
            put("surname_readings", JSONArray(entry.surnameReadings))
            put("given_name_readings", JSONArray(entry.givenReadings))
            put("venue_mode", "auto")
        }

        for (target in listOf(baseUrl, "http://10.0.2.2:8080").distinct()) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$target/api/patient/checkin")
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                if (AppConfig.API_SECRET.isNotEmpty()) {
                    reqBuilder.addHeader("X-Yobareta-Secret", AppConfig.API_SECRET)
                }

                val ok = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    resp.isSuccessful
                }
                if (ok) return@withContext true
            } catch (_: Exception) {
            }
        }
        false
    }

    suspend fun removePatientFromCloud(
        sessionId: String,
        baseUrlOverride: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(baseUrlOverride)
        val reqBuilder = Request.Builder()
            .url("$baseUrl/api/patient/$sessionId")
            .delete()
        if (AppConfig.API_SECRET.isNotEmpty()) {
            reqBuilder.addHeader("X-Yobareta-Secret", AppConfig.API_SECRET)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            resp.isSuccessful
        }
    }

    suspend fun simulateCall(
        transcript: String,
        baseUrlOverride: String = ""
    ): NurseAnnounceResult = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl(baseUrlOverride)
        val bodyJson = JSONObject().apply {
            put("transcript", transcript)
        }

        for (target in listOf(baseUrl, "http://10.0.2.2:8080").distinct()) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$target/api/nurse/simulate")
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                if (AppConfig.API_SECRET.isNotEmpty()) {
                    reqBuilder.addHeader("X-Yobareta-Secret", AppConfig.API_SECRET)
                }

                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val rawBody = resp.body?.string() ?: "{}"
                    val json = JSONObject(rawBody)
                    val summaries = mutableListOf<MatchedPatientSummary>()
                    val arr = json.optJSONArray("matches")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            summaries.add(
                                MatchedPatientSummary(
                                    sessionId = m.optString("session_id", ""),
                                    name = m.optString("name", ""),
                                    ticket = m.optString("ticket", ""),
                                    tier = m.optString("tier", "called"),
                                    destination = m.optString("destination", ""),
                                    instruction = m.optString("instruction", "")
                                )
                            )
                        }
                    }
                    return@withContext NurseAnnounceResult(
                        transcript = json.optString("transcript", ""),
                        matchesCount = json.optInt("matches_count", 0),
                        matchedSummaries = summaries
                    )
                }
            } catch (_: Exception) {
            }
        }
        NurseAnnounceResult(transcript = transcript, matchesCount = 0, matchedSummaries = emptyList())
    }

    /**
     * Queries the host PC via Android Emulator bridge (10.0.2.2:8080) or LAN IP to auto-discover
     * the live public HTTPS Cloud Tunnel URL (`https://....lhr.life`) if running.
     */
    suspend fun discoverHostServerUrl(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf("http://10.0.2.2:8080", AppConfig.DEFAULT_CLOUD_RUN_BASE_URL)
        for (candidate in candidates) {
            try {
                val req = Request.Builder()
                    .url("${candidate.trimEnd('/')}/api/server-info")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = JSONObject(body)
                        val pub = json.optString("public_url", "").trim()
                        if (pub.startsWith("https://")) return@withContext pub
                        val rec = json.optString("recommended_qr_base_url", "").trim()
                        if (rec.isNotEmpty()) return@withContext rec
                    }
                }
            } catch (_: Exception) {
            }
        }
        null
    }
}
