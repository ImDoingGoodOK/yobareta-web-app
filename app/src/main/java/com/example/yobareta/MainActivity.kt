package com.example.yobareta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import java.util.UUID

/**
 * Nurse Station & Reception Desk Tablet Console for Yobareta.
 *
 * Designed for cheap Android tablets placed at reception desks, clinics, or counters:
 * - "Add Customer" flow: input customer name -> auto-assigns temporary ID & sequential number -> displays QR code.
 * - Continuously listens via USB / built-in microphone for spoken announcements.
 * - Manages the daily waiting queue with automatic midnight/capacity reset.
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val discoveryClient = remember { YobaretaCloudClient() }
    var baseUrl by remember { mutableStateOf(AppConfig.DEFAULT_CLOUD_RUN_BASE_URL) }

    // Auto-discover public HTTPS Cloud URL (`https://...lhr.life`) from the host PC (`10.0.2.2:8080`)
    LaunchedEffect(Unit) {
        val discovered = discoveryClient.discoverHostServerUrl()
        if (!discovered.isNullOrBlank()) {
            AppConfig.updateServerBaseUrl(context, discovered)
            baseUrl = AppConfig.DEFAULT_CLOUD_RUN_BASE_URL
        }
    }

    // Active modals
    var showAddCustomerDialog by remember { mutableStateOf(false) }
    var activeQrPatient by remember { mutableStateOf<DailyPatientEntry?>(null) }
    var showCounterStandQr by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showServerUrlDialog by remember { mutableStateOf(false) }

    // Inline Quick Add Customer State
    var nameInput by remember { mutableStateOf("") }
    var selectedDestination by remember { mutableStateOf("3番診察室 (Exam Room 3)") }
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

    // Auto-update readings as staff types customer name
    LaunchedEffect(nameInput) {
        val trimmed = nameInput.trim()
        if (trimmed.isEmpty()) {
            proposedReadings = emptyList()
            return@LaunchedEffect
        }
        readingDebounceJob?.cancel()
        readingDebounceJob = coroutineScope.launch {
            delay(350)
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
                                "Yobareta Console (受付・呼出システム)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                "📅 $currentDate  |  Waiting: $waitingCount / Total: ${queueList.size}  |  🌐 $baseUrl",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { showAddCustomerDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text("➕ Add Customer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { showServerUrlDialog = true },
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text("🌐 Server IP", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showResetConfirmDialog = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("🔄 Reset", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddCustomerDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text("➕ Add Customer (新規QR発行)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
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
                    // Left Pane: Add Customer Card & Desk Mic (42%)
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        QuickAddCustomerCard(
                            nextTicketNumber = nextTicketNumber,
                            nameInput = nameInput,
                            onNameInputChange = { nameInput = it },
                            selectedDestination = selectedDestination,
                            onDestinationChange = { selectedDestination = it },
                            proposedReadings = proposedReadings,
                            onOpenModalClick = { showAddCustomerDialog = true },
                            onRegisterCustomer = {
                                val customerName = nameInput.trim().ifEmpty { "Customer #$nextTicketNumber" }
                                val fullReads = KanjiReadingProposer.buildFullNameHints(customerName, proposedReadings)
                                val surReads = KanjiReadingProposer.buildSurnameHints(customerName, proposedReadings)
                                val givReads = KanjiReadingProposer.buildGivenNameHints(customerName, proposedReadings)

                                val created = onAddPatient(
                                    customerName,
                                    nextTicketNumber.toString(),
                                    selectedDestination,
                                    fullReads,
                                    surReads,
                                    givReads
                                )
                                // Immediately display the QR code for this newly added customer
                                activeQrPatient = created
                                nameInput = ""
                            }
                        )

                        DeskMicBroadcasterCard(
                            isMicGranted = isMicGranted,
                            serviceState = serviceState,
                            onRequestPermissions = onRequestPermissions,
                            onToggleListening = onToggleListening,
                            simCallInput = simCallInput,
                            onSimCallInputChange = { simCallInput = it },
                            onSimulateCall = onSimulateCall
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
                            onAddCustomerClick = { showAddCustomerDialog = true },
                            onShowQr = { activeQrPatient = it },
                            onCallPatient = { p ->
                                onSimulateCall("${p.ticketNumber}番 ${p.name}様、${p.destination}へお入りください")
                            },
                            onMarkCompleted = onMarkCompleted,
                            onRemovePatient = onRemovePatient
                        )
                    }
                }
            } else {
                // Single-column smartphone / portrait layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    QuickAddCustomerCard(
                        nextTicketNumber = nextTicketNumber,
                        nameInput = nameInput,
                        onNameInputChange = { nameInput = it },
                        selectedDestination = selectedDestination,
                        onDestinationChange = { selectedDestination = it },
                        proposedReadings = proposedReadings,
                        onOpenModalClick = { showAddCustomerDialog = true },
                        onRegisterCustomer = {
                            val customerName = nameInput.trim().ifEmpty { "Customer #$nextTicketNumber" }
                            val fullReads = KanjiReadingProposer.buildFullNameHints(customerName, proposedReadings)
                            val surReads = KanjiReadingProposer.buildSurnameHints(customerName, proposedReadings)
                            val givReads = KanjiReadingProposer.buildGivenNameHints(customerName, proposedReadings)

                            val created = onAddPatient(
                                customerName,
                                nextTicketNumber.toString(),
                                selectedDestination,
                                fullReads,
                                surReads,
                                givReads
                            )
                            activeQrPatient = created
                            nameInput = ""
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
                        onAddCustomerClick = { showAddCustomerDialog = true },
                        onShowQr = { activeQrPatient = it },
                        onCallPatient = { p ->
                            onSimulateCall("${p.ticketNumber}番 ${p.name}様、${p.destination}へお入りください")
                        },
                        onMarkCompleted = onMarkCompleted,
                        onRemovePatient = onRemovePatient,
                        modifier = Modifier.height(440.dp)
                    )

                    DeskMicBroadcasterCard(
                        isMicGranted = isMicGranted,
                        serviceState = serviceState,
                        onRequestPermissions = onRequestPermissions,
                        onToggleListening = onToggleListening,
                        simCallInput = simCallInput,
                        onSimCallInputChange = { simCallInput = it },
                        onSimulateCall = onSimulateCall
                    )

                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    // Modal 0: "Add Customer" Input Dialog -> Auto-assigns Temp ID & Number -> Opens QR Code
    if (showAddCustomerDialog) {
        AddCustomerDialog(
            nextTicketNumber = nextTicketNumber,
            onDismiss = { showAddCustomerDialog = false },
            onCustomerAdded = { customerName, destination, fullR, surR, givR ->
                val created = onAddPatient(
                    customerName,
                    nextTicketNumber.toString(),
                    destination,
                    fullR,
                    surR,
                    givR
                )
                showAddCustomerDialog = false
                // Immediately show the QR code modal for this customer!
                activeQrPatient = created
            }
        )
    }

    // Modal 1: Individual Customer QR Code Dialog
    LaunchedEffect(activeQrPatient) {
        if (activeQrPatient != null) {
            val discovered = discoveryClient.discoverHostServerUrl()
            if (!discovered.isNullOrBlank()) {
                AppConfig.updateServerBaseUrl(context, discovered)
                baseUrl = AppConfig.DEFAULT_CLOUD_RUN_BASE_URL
            }
        }
    }

    activeQrPatient?.let { patient ->
        PatientQrCodeDialog(
            patient = patient,
            baseUrl = baseUrl,
            onCallNow = {
                onSimulateCall("${patient.ticketNumber}番 ${patient.name}様、${patient.destination}へお入りください")
            },
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

    // Modal 3: Server URL Settings Dialog
    if (showServerUrlDialog) {
        var urlDraft by remember(baseUrl) { mutableStateOf(baseUrl) }
        AlertDialog(
            onDismissRequest = { showServerUrlDialog = false },
            title = { Text("🌐 Cloud / Server URL Settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter your Cloud Run / Render / Public Tunnel HTTPS URL (or local Wi-Fi IP) so your phone opens the website when scanning the QR code:",
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text("Server Base URL") },
                        placeholder = { Text("https://your-service.run.app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                val discovered = discoveryClient.discoverHostServerUrl()
                                if (!discovered.isNullOrBlank()) {
                                    urlDraft = discovered
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔄 Auto-Detect Public Cloud Tunnel URL")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppConfig.updateServerBaseUrl(context, urlDraft)
                        baseUrl = AppConfig.DEFAULT_CLOUD_RUN_BASE_URL
                        showServerUrlDialog = false
                    }
                ) {
                    Text("Save URL")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal 4: Daily Reset Confirmation Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset Today's Customer Queue?") },
            text = { Text("This clears today's customer list and restarts numbering from #101.") },
            confirmButton = {
                Button(
                    onClick = {
                        onResetQueue(DailyQueueManager.DEFAULT_START_TICKET)
                        showResetConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Queue", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// -----------------------------------------------------------------------------
// Component: Add Customer Modal Dialog (Input Name -> Assign Temp ID -> Show QR)
// -----------------------------------------------------------------------------

@Composable
fun AddCustomerDialog(
    nextTicketNumber: Int,
    onDismiss: () -> Unit,
    onCustomerAdded: (
        name: String,
        destination: String,
        fullReadings: List<String>,
        surnameReadings: List<String>,
        givenReadings: List<String>
    ) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var customerName by remember { mutableStateOf("") }
    var selectedDest by remember { mutableStateOf("3番診察室 (Exam Room 3)") }
    var proposedReadings by remember { mutableStateOf<List<ReadingOption>>(emptyList()) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }
    val previewTempId = remember { UUID.randomUUID().toString().take(8).uppercase() }

    val destinations = listOf(
        "3番診察室 (Exam Room 3)",
        "1番窓口 (Counter 1)",
        "2番窓口 (Counter 2)",
        "受取カウンター (Pickup)",
        "1番診察室 (Room 1)"
    )

    LaunchedEffect(customerName) {
        val trimmed = customerName.trim()
        if (trimmed.isEmpty()) {
            proposedReadings = emptyList()
            return@LaunchedEffect
        }
        debounceJob?.cancel()
        debounceJob = coroutineScope.launch {
            delay(300)
            proposedReadings = KanjiReadingProposer.proposeReadings(trimmed)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "➕ Add New Customer (新規顧客・患者登録)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp
                )

                // Auto-assigned temporary number & ID preview banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Auto-Assigned Number (自動発番)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "#$nextTicketNumber",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                "Temp ID: $previewTempId",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Customer Name Input
                OutlinedTextField(
                    value = customerName,
                    onValueChange = { customerName = it },
                    label = { Text("Customer Name / お名前") },
                    placeholder = { Text("e.g. Alex Smith / 東海林 太郎") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Phonetic reading preview if applicable
                if (proposedReadings.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Voice Recognition Readings (音声認識よみがな):",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            proposedReadings.forEach { opt ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(opt.fullReading, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // Destination selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Destination / 呼び出し先:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        destinations.forEach { dest ->
                            FilterChip(
                                selected = selectedDest == dest,
                                onClick = { selectedDest = dest },
                                label = { Text(dest, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(0.38f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val finalName = customerName.trim().ifEmpty { "Customer #$nextTicketNumber" }
                            val fullR = KanjiReadingProposer.buildFullNameHints(finalName, proposedReadings)
                            val surR = KanjiReadingProposer.buildSurnameHints(finalName, proposedReadings)
                            val givR = KanjiReadingProposer.buildGivenNameHints(finalName, proposedReadings)
                            onCustomerAdded(finalName, selectedDest, fullR, surR, givR)
                        },
                        enabled = customerName.isNotBlank(),
                        modifier = Modifier.weight(0.62f)
                    ) {
                        Text("Assign & Show QR 📲", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: Inline Add Customer Card
// -----------------------------------------------------------------------------

@Composable
fun QuickAddCustomerCard(
    nextTicketNumber: Int,
    nameInput: String,
    onNameInputChange: (String) -> Unit,
    selectedDestination: String,
    onDestinationChange: (String) -> Unit,
    proposedReadings: List<ReadingOption>,
    onOpenModalClick: () -> Unit,
    onRegisterCustomer: () -> Unit
) {
    val commonDests = listOf(
        "3番診察室 (Exam Room 3)",
        "1番窓口 (Counter 1)",
        "2番窓口 (Counter 2)",
        "受取カウンター (Pickup)",
        "会計窓口 (Cashier)"
    )

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
                Column {
                    Text(
                        "➕ Add Customer (新規顧客・受付)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Next Auto-Assigned Number: #$nextTicketNumber",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(onClick = onOpenModalClick) {
                    Text("Popup Form", fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = nameInput,
                onValueChange = onNameInputChange,
                label = { Text("Customer Name / お名前を入力") },
                placeholder = { Text("e.g. Alex Smith / 東海林 太郎") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Proposed Reading Chips
            if (proposedReadings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Phonetic Readings (音声認識用):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            Spacer(modifier = Modifier.height(10.dp))
            Text("Destination / 案内先:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                        label = { Text(dest, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onRegisterCustomer,
                enabled = nameInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "➕ Add Customer (#$nextTicketNumber) & Generate QR 📲",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
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
                    "🎙️ Desk Microphone (呼出マイク)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                val badgeColor = if (serviceState.isListening) Color(0xFF16A34A) else Color(0xFF64748B)
                val badgeText = if (serviceState.isListening) "Listening" else "Idle"
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
                    Text("Grant Microphone Permission")
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
                        if (serviceState.isListening) "Stop Desk Mic" else "Start Desk Mic (常時集音開始)",
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
                Text("Audio Level (VAD)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            "📢 Recognized: \"${serviceState.latestTranscript}\"",
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
            Text("Manual Call Trigger (テキストで呼び出し通知)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                    Text("Call 📣", fontSize = 12.sp)
                }
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
    onAddCustomerClick: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📋 Today's Customer Queue (本日の待合リスト)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Button(onClick = onAddCustomerClick) {
                    Text("➕ Add Customer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { onSelectTab(0) }, text = { Text("All ($totalCount)") })
                Tab(selected = selectedTab == 1, onClick = { onSelectTab(1) }, text = { Text("Waiting ($waitingCount)") })
                Tab(selected = selectedTab == 2, onClick = { onSelectTab(2) }, text = { Text("Called ($calledCount)") })
                Tab(selected = selectedTab == 3, onClick = { onSelectTab(3) }, text = { Text("Done ($completedCount)") })
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (patients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "No customers in queue yet.\nTap '➕ Add Customer' to enter a name, assign a temporary ID/number, and show their QR code.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                        Button(onClick = onAddCustomerClick) {
                            Text("➕ Add First Customer")
                        }
                    }
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
            // Left info: Ticket, Name, Temp ID, Dest
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            patient.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            "(ID: ${patient.id})",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "📍 ${patient.destination}  •  ${patient.waitMinutes}m ago",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Right actions: Status + QR + Call + Done
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

                OutlinedButton(
                    onClick = onShowQr,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("📱 QR", fontSize = 12.sp)
                }
                Button(
                    onClick = onCall,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("📣 Call", fontSize = 12.sp)
                }
                if (patient.status != PatientQueueStatus.COMPLETED) {
                    IconButton(onClick = onDone, modifier = Modifier.size(32.dp)) {
                        Text("✅", fontSize = 16.sp)
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Text("🗑️", fontSize = 15.sp)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Component: Modals (Customer QR Code & Countertop Stand QR)
// -----------------------------------------------------------------------------

@Composable
fun PatientQrCodeDialog(
    patient: DailyPatientEntry,
    baseUrl: String,
    onCallNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val pwaUrl = patient.toPwaUrl(baseUrl)
    val qrBitmap = remember(pwaUrl) {
        QrCodeBitmapGenerator.encodeToBitmap(pwaUrl, sizePx = 512)
    }
    var callTriggered by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "Assigned Number: #${patient.ticketNumber}  |  Temp ID: ${patient.id}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    patient.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Destination: ${patient.destination}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Customer QR Code",
                    modifier = Modifier
                        .size(230.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    pwaUrl,
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    "Scan this QR code with your phone camera.\nYour phone will open your personal waiting page and vibrate/chime when #${patient.ticketNumber} is called.",
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quick Call Button right inside the QR modal for instant testing!
                Button(
                    onClick = {
                        onCallNow()
                        callTriggered = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (callTriggered) Color(0xFF16A34A) else MaterialTheme.colorScheme.secondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (callTriggered) "✅ Call Sent to Phone! (Tap to Recall 📣)" else "📣 Test Call This Customer Now",
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done / Close (閉じる)")
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
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Wait Anywhere 🌿 (院外待機QR)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Scan to open the Yobareta web app",
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

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    standUrl,
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}
