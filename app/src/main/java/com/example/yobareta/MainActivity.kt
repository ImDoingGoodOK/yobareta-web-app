package com.example.yobareta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.yobareta.auth.AuthManager
import com.example.yobareta.model.DailyPatientEntry
import com.example.yobareta.model.PatientQueueStatus
import com.example.yobareta.net.YobaretaCloudClient
import com.example.yobareta.qr.QrCodeBitmapGenerator
import com.example.yobareta.queue.DailyQueueManager
import com.example.yobareta.service.ListenService
import com.example.yobareta.service.ListenServiceUiState
import com.example.yobareta.setup.KanjiReadingProposer
import com.example.yobareta.setup.ReadingOption
import com.example.yobareta.ui.theme.YobaretaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Nurse Station & Reception Desk Tablet Console for Yobareta.
 *
 * Designed for cheap Android tablets placed at reception desks or nurse stations:
 * - Continuously listens via USB / built-in microphone for spoken announcements.
 * - Manages the daily waiting queue with automatic midnight reset.
 * - Generates scannable QR codes for each patient to launch their zero-install PWA.
 * - Displays live audio VU meter and push-alert delivery status.
 */
class MainActivity : ComponentActivity() {

    private val authManager by lazy { AuthManager() }
    private val cloudClient by lazy { YobaretaCloudClient(authManager) }
    private lateinit var queueManager: DailyQueueManager

    private val micPermissionGranted = mutableStateOf(false)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        micPermissionGranted.value = results[Manifest.permission.RECORD_AUDIO] == true ||
            isAudioPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppConfig.initFromContext(applicationContext)
        AuthManager.initAppCheck(applicationContext)
        queueManager = DailyQueueManager(applicationContext, cloudClient)
        ListenService.queueManager = queueManager
        micPermissionGranted.value = isAudioPermissionGranted()

        lifecycleScope.launch {
            authManager.ensureAuthenticated()
        }

        setContent {
            YobaretaTheme {
                val serviceState by ListenService.uiState.collectAsState()
                val isMicGranted by micPermissionGranted
                val queueList by queueManager.patients.collectAsState()
                val currentDate by queueManager.currentDate.collectAsState()
                val nextTicketNumber by queueManager.nextTicketNumber.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NurseDeskTabletScreen(
                        isMicGranted = isMicGranted,
                        serviceState = serviceState,
                        queueList = queueList,
                        currentDate = currentDate,
                        nextTicketNumber = nextTicketNumber,
                        onRequestPermissions = { requestAudioAndNotificationPermissions() },
                        onToggleListening = { start ->
                            if (start) {
                                val intent = Intent(this, ListenService::class.java).apply {
                                    action = ListenService.ACTION_START
                                }
                                ContextCompat.startForegroundService(this, intent)
                            } else {
                                val intent = Intent(this, ListenService::class.java).apply {
                                    action = ListenService.ACTION_STOP
                                }
                                startService(intent)
                            }
                        },
                        onAddPatient = { name, ticket, dest, fullR, surR, givR ->
                            queueManager.addPatient(
                                name = name,
                                ticketInput = ticket,
                                destination = dest,
                                fullReadings = fullR,
                                surnameReadings = surR,
                                givenReadings = givR
                            )
                        },
                        onMarkCompleted = { id -> queueManager.markPatientCompleted(id) },
                        onRemovePatient = { id -> queueManager.removePatient(id) },
                        onResetQueue = { startTicket -> queueManager.resetQueue(startTicket) },
                        onSimulateCall = { text ->
                            lifecycleScope.launch {
                                try {
                                    val res = cloudClient.simulateCall(text)
                                    if (res.matchesCount > 0) {
                                        res.matchedSummaries.forEach { m ->
                                            queueManager.markPatientCalledByMatch(
                                                m.ticket.ifEmpty { m.name },
                                                m.destination
                                            )
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        micPermissionGranted.value = isAudioPermissionGranted()
        queueManager.checkAndPerformDailyReset()
    }

    private fun isAudioPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioAndNotificationPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(perms.toTypedArray())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NurseDeskTabletScreen(
    isMicGranted: Boolean,
    serviceState: ListenServiceUiState,
    queueList: List<DailyPatientEntry>,
    currentDate: String,
    nextTicketNumber: Int,
    onRequestPermissions: () -> Unit,
    onToggleListening: (Boolean) -> Unit,
    onAddPatient: (String, String, String, List<String>, List<String>, List<String>) -> DailyPatientEntry,
    onMarkCompleted: (String) -> Unit,
    onRemovePatient: (String) -> Unit,
    onResetQueue: (Int) -> Unit,
    onSimulateCall: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val baseUrl = AppConfig.DEFAULT_CLOUD_RUN_BASE_URL

    // Active modals
    var activeQrPatient by remember { mutableStateOf<DailyPatientEntry?>(null) }
    var showCounterStandQr by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // Patient Form State
    var nameInput by remember { mutableStateOf("") }
    var ticketInput by remember { mutableStateOf("") }
    var selectedDestination by remember { mutableStateOf("3番診察室 (Exam Room 3)") }
    var customDestInput by remember { mutableStateOf("") }
    var proposedReadings by remember { mutableStateOf<List<ReadingOption>>(emptyList()) }
    var readingDebounceJob by remember { mutableStateOf<Job?>(null) }

    // Quick Simulation input
    var simCallInput by remember { mutableStateOf("東海林太郎様、3番診察室へお入りください") }

    // Queue Filter Tab
    var selectedTab by remember { mutableStateOf(0) } // 0: All, 1: Waiting, 2: Called, 3: Completed

    val waitingCount = queueList.count { it.status == PatientQueueStatus.WAITING }
    val calledCount = queueList.count { it.status == PatientQueueStatus.CALLED || it.status == PatientQueueStatus.RECALL }
    val completedCount = queueList.count { it.status == PatientQueueStatus.COMPLETED }

    val filteredList = when (selectedTab) {
        1 -> queueList.filter { it.status == PatientQueueStatus.WAITING }
        2 -> queueList.filter { it.status == PatientQueueStatus.CALLED || it.status == PatientQueueStatus.RECALL }
        3 -> queueList.filter { it.status == PatientQueueStatus.COMPLETED }
        else -> queueList
    }

    // Auto-update readings as nurse types patient name
    LaunchedEffect(nameInput) {
        val trimmed = nameInput.trim()
        if (trimmed.isEmpty()) {
            proposedReadings = emptyList()
            return@LaunchedEffect
        }
        readingDebounceJob?.cancel()
        readingDebounceJob = coroutineScope.launch {
            delay(400)
            proposedReadings = KanjiReadingProposer.proposeReadings(trimmed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("🏥", fontSize = 24.sp)
                        Column {
                            Text(
                                "Yobareta Nurse Desk (受付・ナースステーション)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "📅 $currentDate  |  待合: ${waitingCount}名 / 本日合計: ${queueList.size}名",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { showCounterStandQr = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("📲 受付スタンドQR", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { showResetConfirmDialog = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("🔄 本日リセット", fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            val isTabletWide = maxWidth >= 760.dp

            if (isTabletWide) {
                // Two-column tablet layout
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Pane: Broadcaster & Check-in (42%)
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DeskMicBroadcasterCard(
                            isMicGranted = isMicGranted,
                            serviceState = serviceState,
                            onRequestPermissions = onRequestPermissions,
                            onToggleListening = onToggleListening,
                            simCallInput = simCallInput,
                            onSimCallInputChange = { simCallInput = it },
                            onSimulateCall = onSimulateCall
                        )

                        NewPatientCheckinCard(
                            nextTicketNumber = nextTicketNumber,
                            nameInput = nameInput,
                            onNameInputChange = { nameInput = it },
                            ticketInput = ticketInput,
                            onTicketInputChange = { ticketInput = it },
                            selectedDestination = selectedDestination,
                            onDestinationChange = { selectedDestination = it },
                            customDestInput = customDestInput,
                            onCustomDestInputChange = { customDestInput = it },
                            proposedReadings = proposedReadings,
                            onRegisterPatient = {
                                val fullReads = KanjiReadingProposer.buildFullNameHints(nameInput, proposedReadings)
                                val surReads = KanjiReadingProposer.buildSurnameHints(nameInput, proposedReadings)
                                val givReads = KanjiReadingProposer.buildGivenNameHints(nameInput, proposedReadings)
                                val finalDest = if (selectedDestination == "Custom") customDestInput else selectedDestination

                                val created = onAddPatient(
                                    nameInput,
                                    ticketInput.ifEmpty { nextTicketNumber.toString() },
                                    finalDest,
                                    fullReads,
                                    surReads,
                                    givReads
                                )
                                // Auto-show the QR code modal for this newly registered patient!
                                activeQrPatient = created
                                nameInput = ""
                                ticketInput = ""
                            }
                        )
                    }

                    // Right Pane: Live Queue List (58%)
                    Column(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                    ) {
                        TodayQueueCard(
                            selectedTab = selectedTab,
                            onSelectTab = { selectedTab = it },
                            totalCount = queueList.size,
                            waitingCount = waitingCount,
                            calledCount = calledCount,
                            completedCount = completedCount,
                            patients = filteredList,
                            onShowQr = { activeQrPatient = it },
                            onCallPatient = { p ->
                                onSimulateCall("${p.name}様、${p.destination}へお入りください")
                            },
                            onMarkCompleted = onMarkCompleted,
                            onRemovePatient = onRemovePatient
                        )
                    }
                }
            } else {
                // Single-column smartphone / small portrait tablet fallback
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DeskMicBroadcasterCard(
                        isMicGranted = isMicGranted,
                        serviceState = serviceState,
                        onRequestPermissions = onRequestPermissions,
                        onToggleListening = onToggleListening,
                        simCallInput = simCallInput,
                        onSimCallInputChange = { simCallInput = it },
                        onSimulateCall = onSimulateCall
                    )

                    NewPatientCheckinCard(
                        nextTicketNumber = nextTicketNumber,
                        nameInput = nameInput,
                        onNameInputChange = { nameInput = it },
                        ticketInput = ticketInput,
                        onTicketInputChange = { ticketInput = it },
                        selectedDestination = selectedDestination,
                        onDestinationChange = { selectedDestination = it },
                        customDestInput = customDestInput,
                        onCustomDestInputChange = { customDestInput = it },
                        proposedReadings = proposedReadings,
                        onRegisterPatient = {
                            val fullReads = KanjiReadingProposer.buildFullNameHints(nameInput, proposedReadings)
                            val surReads = KanjiReadingProposer.buildSurnameHints(nameInput, proposedReadings)
                            val givReads = KanjiReadingProposer.buildGivenNameHints(nameInput, proposedReadings)
                            val finalDest = if (selectedDestination == "Custom") customDestInput else selectedDestination

                            val created = onAddPatient(
                                nameInput,
                                ticketInput.ifEmpty { nextTicketNumber.toString() },
                                finalDest,
                                fullReads,
                                surReads,
                                givReads
                            )
                            activeQrPatient = created
                            nameInput = ""
                            ticketInput = ""
                        }
                    )

                    TodayQueueCard(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        totalCount = queueList.size,
                        waitingCount = waitingCount,
                        calledCount = calledCount,
                        completedCount = completedCount,
                        patients = filteredList,
                        onShowQr = { activeQrPatient = it },
                        onCallPatient = { p ->
                            onSimulateCall("${p.name}様、${p.destination}へお入りください")
                        },
                        onMarkCompleted = onMarkCompleted,
                        onRemovePatient = onRemovePatient,
                        modifier = Modifier.height(480.dp)
                    )
                }
            }
        }
    }

    // Modal 1: Individual Patient QR Code Dialog
    activeQrPatient?.let { patient ->
        PatientQrCodeDialog(
            patient = patient,
            baseUrl = baseUrl,
            onDismiss = { activeQrPatient = null }
        )
    }

    // Modal 2: Countertop Stand QR Code Dialog
    if (showCounterStandQr) {
        CounterStandQrDialog(
            baseUrl = baseUrl,
            onDismiss = { showCounterStandQr = false }
        )
    }

    // Modal 3: Daily Reset Confirmation Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("本日の順番待ちをリセットしますか？") },
            text = { Text("本日の登録患者リストをクリアし、受付番号を #101 から再開します。") },
            confirmButton = {
                Button(
                    onClick = {
                        onResetQueue(DailyQueueManager.DEFAULT_START_TICKET)
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("リセット実行", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// Component: Desk Microphone Broadcaster Card
// -----------------------------------------------------------------------------

@Composable
fun DeskMicBroadcasterCard(
    isMicGranted: Boolean,
    serviceState: ListenServiceUiState,
    onRequestPermissions: () -> Unit,
    onToggleListening: (Boolean) -> Unit,
    simCallInput: String,
    onSimCallInputChange: (String) -> Unit,
    onSimulateCall: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🎙️ 受付・呼出マイク (Desk Mic)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                val badgeColor = if (serviceState.isListening) Color(0xFF16A34A) else Color(0xFF64748B)
                val badgeText = if (serviceState.isListening) "稼働中 (Listening)" else "停止中 (Idle)"
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        badgeText,
                        color = badgeColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!isMicGranted) {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("マイク権限を許可してください")
                }
            } else {
                Button(
                    onClick = { onToggleListening(!serviceState.isListening) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceState.isListening) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (serviceState.isListening) "マイクを停止する" else "受付マイクを開始する (常時集音)",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // VU Meter Level
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("音量レベル (VAD)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${serviceState.currentDbFs.toInt()} dBFS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val vuProgress = ((serviceState.currentDbFs + 60f) / 60f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { vuProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (vuProgress > 0.8f) Color(0xFFDC2626) else if (vuProgress > 0.5f) Color(0xFFF59E0B) else Color(0xFF16A34A),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            // Latest Recognized Announcement
            if (serviceState.latestTranscript.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "📢 最新認識: \"${serviceState.latestTranscript}\"",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (serviceState.latestMatchedInfo.isNotEmpty()) {
                            Text(
                                "👉 ${serviceState.latestMatchedInfo}",
                                fontSize = 12.sp,
                                color = Color(0xFF16A34A),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Quick Manual Simulation Call
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))
            Text("手動呼出テスト (音声なしでプッシュ通知)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = simCallInput,
                    onValueChange = onSimCallInputChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                    singleLine = true
                )
                Button(
                    onClick = { onSimulateCall(simCallInput) },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("呼出", fontSize = 12.sp)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: New Patient Check-in Card (Reception Desk)
// -----------------------------------------------------------------------------

@Composable
fun NewPatientCheckinCard(
    nextTicketNumber: Int,
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    ticketInput: String,
    onTicketInputChange: (String) -> Unit,
    selectedDestination: String,
    onDestinationChange: (String) -> Unit,
    customDestInput: String,
    onCustomDestInputChange: (String) -> Unit,
    proposedReadings: List<ReadingOption>,
    onRegisterPatient: () -> Unit
) {
    val commonDests = listOf("3番診察室 (Exam Room 3)", "1番診察室", "2番診察室", "2番窓口", "会計窓口", "お薬窓口")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("📝 新規患者受付 (Check-in)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            // Ticket & Name Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = ticketInput,
                    onValueChange = onTicketInputChange,
                    label = { Text("番号 (#$nextTicketNumber)") },
                    placeholder = { Text("#$nextTicketNumber") },
                    modifier = Modifier.weight(0.35f),
                    singleLine = true
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = onNameInputChange,
                    label = { Text("お名前 (漢字・英語)") },
                    placeholder = { Text("例: 東海林 太郎") },
                    modifier = Modifier.weight(0.65f),
                    singleLine = true
                )
            }

            // Proposed Reading Chips
            if (proposedReadings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("よみがな候補 (音声認識用):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    proposedReadings.forEach { opt ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${opt.fullReading} (${opt.surnameReading})", fontSize = 11.sp) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("行先 (Destination):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                commonDests.forEach { dest ->
                    FilterChip(
                        selected = selectedDestination == dest,
                        onClick = { onDestinationChange(dest) },
                        label = { Text(dest.take(10), fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onRegisterPatient,
                enabled = nameInput.isNotBlank() || ticketInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("受付登録 ＆ QRコード表示 📲", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: Today's Queue Card
// -----------------------------------------------------------------------------

@Composable
fun TodayQueueCard(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    totalCount: Int,
    waitingCount: Int,
    calledCount: Int,
    completedCount: Int,
    patients: List<DailyPatientEntry>,
    onShowQr: (DailyPatientEntry) -> Unit,
    onCallPatient: (DailyPatientEntry) -> Unit,
    onMarkCompleted: (String) -> Unit,
    onRemovePatient: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("📋 本日の待合リスト (Daily Queue)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { onSelectTab(0) }, text = { Text("すべて ($totalCount)") })
                Tab(selected = selectedTab == 1, onClick = { onSelectTab(1) }, text = { Text("待合中 ($waitingCount)") })
                Tab(selected = selectedTab == 2, onClick = { onSelectTab(2) }, text = { Text("呼出中 ($calledCount)") })
                Tab(selected = selectedTab == 3, onClick = { onSelectTab(3) }, text = { Text("完了 ($completedCount)") })
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (patients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "登録された患者様はいません。\n左側のフォームから受付登録を行ってください。",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(patients, key = { it.id }) { patient ->
                        PatientQueueRow(
                            patient = patient,
                            onShowQr = { onShowQr(patient) },
                            onCall = { onCallPatient(patient) },
                            onDone = { onMarkCompleted(patient.id) },
                            onDelete = { onRemovePatient(patient.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PatientQueueRow(
    patient: DailyPatientEntry,
    onShowQr: () -> Unit,
    onCall: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (patient.status) {
        PatientQueueStatus.WAITING -> Color(0xFF0284C7)
        PatientQueueStatus.CALLED -> Color(0xFF16A34A)
        PatientQueueStatus.RECALL -> Color(0xFFDC2626)
        PatientQueueStatus.COMPLETED -> Color(0xFF64748B)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left info: Ticket, Name, Dest
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Ticket Number Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "#${patient.ticketNumber}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Column {
                    Text(
                        patient.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        "📍 ${patient.destination}  •  ${patient.waitMinutes}分前",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right actions: Status + Buttons
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        patient.status.name,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                IconButton(onClick = onShowQr, modifier = Modifier.size(36.dp)) {
                    Text("📱", fontSize = 18.sp)
                }
                IconButton(onClick = onCall, modifier = Modifier.size(36.dp)) {
                    Text("📣", fontSize = 18.sp)
                }
                if (patient.status != PatientQueueStatus.COMPLETED) {
                    IconButton(onClick = onDone, modifier = Modifier.size(36.dp)) {
                        Text("✅", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: Modals (Patient QR Code & Countertop Stand QR)
// -----------------------------------------------------------------------------

@Composable
fun PatientQrCodeDialog(
    patient: DailyPatientEntry,
    baseUrl: String,
    onDismiss: () -> Unit
) {
    val pwaUrl = patient.toPwaUrl(baseUrl)
    val qrBitmap = remember(pwaUrl) {
        QrCodeBitmapGenerator.encodeToBitmap(pwaUrl, sizePx = 512)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterAlignment
            ) {
                Text(
                    "#${patient.ticketNumber}  ${patient.name}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "行先: ${patient.destination}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Patient QR Code",
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "院外（カフェ・お車等）でお待ちの方はこちらをお手元のスマホでスキャンしてください。\n呼び出し時にスマホが振動・通知します。",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("閉じる")
                }
            }
        }
    }
}

@Composable
fun CounterStandQrDialog(
    baseUrl: String,
    onDismiss: () -> Unit
) {
    val standUrl = baseUrl.trimEnd('/') + "/"
    val qrBitmap = remember(standUrl) {
        QrCodeBitmapGenerator.encodeToBitmap(standUrl, sizePx = 600)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterAlignment
            ) {
                Text(
                    "院外でお待ちいただけます 🌿",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "カフェ・屋外ガーデン・お車などで待機可能",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Counter Stand QR Code",
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    "スマートフォンで上記QRコードを読み取り、\n受付番号またはお名前をご入力ください。\n順番になるとスマホが強く振動してご案内します。",
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("受付画面に戻る")
                }
            }
        }
    }
}
