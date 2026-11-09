package com.example.yobareta.queue

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.yobareta.AppConfig
import com.example.yobareta.model.DailyPatientEntry
import com.example.yobareta.model.PatientQueueStatus
import com.example.yobareta.net.YobaretaCloudClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the daily patient queue on the nurse desk tablet.
 *
 * Features:
 * - Automatically resets every calendar day (comparing YYYY-MM-DD).
 * - Optional capacity auto-reset when reaching threshold (e.g. 150 patients).
 * - Manages auto-incrementing ticket numbers (#101, #102...).
 * - Syncs check-ins with Cloud Run in the background.
 */
class DailyQueueManager(
    private val context: Context,
    private val cloudClient: YobaretaCloudClient = YobaretaCloudClient()
) {
    companion object {
        private const val TAG = "DailyQueueManager"
        private const val PREFS_NAME = "yobareta_daily_queue_prefs"
        private const val KEY_DATE = "queue_date"
        private const val KEY_NEXT_TICKET = "queue_next_ticket"
        private const val KEY_PATIENTS_JSON = "queue_patients_json"
        private const val KEY_MAX_CAPACITY = "queue_max_capacity"
        const val DEFAULT_START_TICKET = 101
        const val DEFAULT_MAX_CAPACITY = 150
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _patients = MutableStateFlow<List<DailyPatientEntry>>(emptyList())
    val patients: StateFlow<List<DailyPatientEntry>> = _patients.asStateFlow()

    private val _currentDate = MutableStateFlow(getTodayDateString())
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    private val _nextTicketNumber = MutableStateFlow(DEFAULT_START_TICKET)
    val nextTicketNumber: StateFlow<Int> = _nextTicketNumber.asStateFlow()

    init {
        checkAndPerformDailyReset()
        loadQueueFromStorage()
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * Checks if a new day has arrived. If so, clears yesterday's queue and resets ticket counter.
     */
    fun checkAndPerformDailyReset(): Boolean {
        val today = getTodayDateString()
        val savedDate = prefs.getString(KEY_DATE, "")
        _currentDate.value = today

        if (savedDate != today) {
            Log.i(TAG, "New day detected ($savedDate -> $today). Resetting daily queue.")
            resetQueue(startTicket = DEFAULT_START_TICKET)
            return true
        }
        return false
    }

    private fun loadQueueFromStorage() {
        val jsonStr = prefs.getString(KEY_PATIENTS_JSON, "[]") ?: "[]"
        val nextTkt = prefs.getInt(KEY_NEXT_TICKET, DEFAULT_START_TICKET)
        _nextTicketNumber.value = nextTkt

        try {
            val jsonArr = JSONArray(jsonStr)
            val list = mutableListOf<DailyPatientEntry>()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val statusStr = obj.optString("status", PatientQueueStatus.WAITING.name)
                val status = try {
                    PatientQueueStatus.valueOf(statusStr)
                } catch (_: Exception) {
                    PatientQueueStatus.WAITING
                }

                val fullReadings = mutableListOf<String>()
                val fArr = obj.optJSONArray("fullReadings")
                if (fArr != null) {
                    for (j in 0 until fArr.length()) fullReadings.add(fArr.getString(j))
                }

                val surReadings = mutableListOf<String>()
                val sArr = obj.optJSONArray("surnameReadings")
                if (sArr != null) {
                    for (j in 0 until sArr.length()) surReadings.add(sArr.getString(j))
                }

                list.add(
                    DailyPatientEntry(
                        id = obj.getString("id"),
                        ticketNumber = obj.getString("ticketNumber"),
                        name = obj.getString("name"),
                        fullReadings = fullReadings,
                        surnameReadings = surReadings,
                        destination = obj.optString("destination", "診察室 (Exam Room)"),
                        checkinTimeMs = obj.optLong("checkinTimeMs", System.currentTimeMillis()),
                        lastCalledTimeMs = if (obj.has("lastCalledTimeMs")) obj.getLong("lastCalledTimeMs") else null,
                        callCount = obj.optInt("callCount", 0),
                        status = status
                    )
                )
            }
            _patients.value = list
        } catch (e: Exception) {
            Log.e(TAG, "Error loading queue from storage: ${e.message}")
            _patients.value = emptyList()
        }
    }

    private fun saveQueueToStorage(list: List<DailyPatientEntry>) {
        try {
            val jsonArr = JSONArray()
            for (p in list) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("ticketNumber", p.ticketNumber)
                    put("name", p.name)
                    put("destination", p.destination)
                    put("checkinTimeMs", p.checkinTimeMs)
                    if (p.lastCalledTimeMs != null) put("lastCalledTimeMs", p.lastCalledTimeMs)
                    put("callCount", p.callCount)
                    put("status", p.status.name)
                    put("fullReadings", JSONArray(p.fullReadings))
                    put("surnameReadings", JSONArray(p.surnameReadings))
                }
                jsonArr.put(obj)
            }
            prefs.edit()
                .putString(KEY_PATIENTS_JSON, jsonArr.toString())
                .putString(KEY_DATE, getTodayDateString())
                .putInt(KEY_NEXT_TICKET, _nextTicketNumber.value)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving queue to storage: ${e.message}")
        }
    }

    fun addPatient(
        name: String,
        ticketInput: String = "",
        destination: String = "3番診察室 (Exam Room 3)",
        fullReadings: List<String> = emptyList(),
        surnameReadings: List<String> = emptyList(),
        givenReadings: List<String> = emptyList()
    ): DailyPatientEntry {
        checkAndPerformDailyReset()

        // Check max threshold
        val maxCap = prefs.getInt(KEY_MAX_CAPACITY, DEFAULT_MAX_CAPACITY)
        if (_patients.value.size >= maxCap) {
            Log.w(TAG, "Max capacity reached ($maxCap). Rolling over queue.")
            resetQueue(startTicket = DEFAULT_START_TICKET)
        }

        val assignedTicket = ticketInput.trim().ifEmpty {
            val tkt = _nextTicketNumber.value.toString()
            _nextTicketNumber.value += 1
            tkt
        }

        val entry = DailyPatientEntry(
            ticketNumber = assignedTicket,
            name = name.trim(),
            destination = destination.trim(),
            fullReadings = fullReadings,
            surnameReadings = surnameReadings,
            givenReadings = givenReadings
        )

        val updated = _patients.value + entry
        _patients.value = updated
        saveQueueToStorage(updated)

        // Sync with Cloud Run queue
        scope.launch {
            try {
                cloudClient.registerPatientInCloud(entry)
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing patient checkin to Cloud Run: ${e.message}")
            }
        }

        return entry
    }

    fun markPatientCalledByMatch(matchedTrigger: String, destination: String): DailyPatientEntry? {
        val currentList = _patients.value.toMutableList()
        val index = currentList.indexOfFirst { p ->
            p.status != PatientQueueStatus.COMPLETED && (
                p.ticketNumber.equals(matchedTrigger, ignoreCase = true) ||
                p.name.contains(matchedTrigger, ignoreCase = true) ||
                p.fullReadings.any { it.contains(matchedTrigger, ignoreCase = true) } ||
                p.surnameReadings.any { it.contains(matchedTrigger, ignoreCase = true) }
            )
        }

        if (index >= 0) {
            val existing = currentList[index]
            val newStatus = if (existing.status == PatientQueueStatus.WAITING) {
                PatientQueueStatus.CALLED
            } else {
                PatientQueueStatus.RECALL
            }
            val updatedEntry = existing.copy(
                status = newStatus,
                lastCalledTimeMs = System.currentTimeMillis(),
                callCount = existing.callCount + 1,
                destination = destination.ifEmpty { existing.destination }
            )
            currentList[index] = updatedEntry
            _patients.value = currentList
            saveQueueToStorage(currentList)
            return updatedEntry
        }
        return null
    }

    fun markPatientCompleted(id: String) {
        val currentList = _patients.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) {
            currentList[index] = currentList[index].copy(status = PatientQueueStatus.COMPLETED)
            _patients.value = currentList
            saveQueueToStorage(currentList)
        }
    }

    fun removePatient(id: String) {
        val updated = _patients.value.filter { it.id != id }
        _patients.value = updated
        saveQueueToStorage(updated)

        scope.launch {
            try {
                cloudClient.removePatientFromCloud(id)
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing patient from Cloud Run: ${e.message}")
            }
        }
    }

    fun resetQueue(startTicket: Int = DEFAULT_START_TICKET) {
        _patients.value = emptyList()
        _nextTicketNumber.value = startTicket
        _currentDate.value = getTodayDateString()
        prefs.edit()
            .putString(KEY_PATIENTS_JSON, "[]")
            .putString(KEY_DATE, getTodayDateString())
            .putInt(KEY_NEXT_TICKET, startTicket)
            .apply()
        Log.i(TAG, "Daily queue reset complete. Starting ticket: #$startTicket")
    }
}
