package com.example.ui

import android.app.Application
import android.location.Location
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// --- MAIN VIEWMODEL IMPLEMENTATION ---

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    val repository = SmartAttendRepository(database)

    // Active User Role Switcher for MVP Evaluation
    var activeRole by mutableStateOf("STUDENT") // STUDENT, FACULTY, ADMIN
    var currentStudentId by mutableStateOf("std01") // Pranav Godbole by default

    // Local Simulation States
    var simulatedIsOnCampus by mutableStateOf(true) // True = On-Campus, False = Off-Campus Proxy
    var simulatedDeviceSpoofState by mutableStateOf(false) // If true, simulates duplicate device ID binding

    // Active QR Token Generator states
    private val _qrToken = MutableStateFlow("NUS-9028-PINAC-COMP-882")
    val qrToken: StateFlow<String> = _qrToken.asStateFlow()
    
    private val _qrTimerRemaining = MutableStateFlow(30)
    val qrTimerRemaining: StateFlow<Int> = _qrTimerRemaining.asStateFlow()

    // Screen selection inside tabs (0: Home, 1: AI Insights, 2: Announcements/Invoices, 3: Audits/History)
    var selectedTab by mutableStateOf(0)

    // Data streams from Room
    val studentsList = repository.students.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val attendanceRecords = repository.allAttendance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val leaveRequests = repository.allLeaves.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lecturesList = repository.allLectures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val alertsList = repository.allAlerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI interactive overlay states
    var showMarkAttendanceDialog by mutableStateOf(false)
    var showApplyLeaveDialog by mutableStateOf(false)
    var showSendBroadcastDialog by mutableStateOf(false)
    var showQRScannerSimulation by mutableStateOf(false)
    var showQRExpandingCodeDialog by mutableStateOf(false)

    // Gemini states
    var isGeneratingNarrative by mutableStateOf(false)
    var monthlyNarrativeOutput by mutableStateOf("")
    
    var isGeneratingRiskExplanation by mutableStateOf(false)
    var riskExplanationOutput by mutableStateOf("")
    var explainedStudentName by mutableStateOf("")

    // Notification toast simulated queue
    var notificationToastMessage by mutableStateOf<String?>(null)

    init {
        // Pre-seed tables and launch periodic rotating QR routine
        viewModelScope.launch {
            repository.checkAndSeedDatabase()
        }
        startQRRotationTimer()
    }

    private fun startQRRotationTimer() {
        viewModelScope.launch {
            while (true) {
                for (seconds in 30 downTo 1) {
                    _qrTimerRemaining.value = seconds
                    delay(1000)
                }
                // Generate a randomized SHA token mock
                val randomHex = UUID.randomUUID().toString().take(8).uppercase()
                _qrToken.value = "HMACSHA256-$randomHex-PINAC-${(100..999).random()}"
                
                // If faculty is hosting a live lecture, update the token in SQLite
                val activeLectures = database.lectureDao().getAllLectures().first().filter { it.status == "Active" }
                if (activeLectures.isNotEmpty()) {
                    val target = activeLectures.first()
                    repository.updateLecture(target.copy(qrToken = _qrToken.value))
                }
            }
        }
    }

    // STUDENT: Mark attendance simulation (GPS verification & QR Matching)
    fun simulateMarkAttendance(method: String, providedQR: String) {
        viewModelScope.launch {
            val student = database.studentDao().getStudentById(currentStudentId) ?: return@launch
            val activeLecture = database.lectureDao().getAllLectures().first().firstOrNull { it.status == "Active" }
            
            if (activeLecture == null) {
                showToast("No active lecture found right now!")
                return@launch
            }

            // Radius computation
            // Campus center: 18.5204, 73.8567
            val campusLat = 18.5204
            val campusLng = 73.8567
            
            val (studentLat, studentLng) = if (simulatedIsOnCampus) {
                Pair(18.52038, 73.85671) // ~3 meters away (On Campus!)
            } else {
                Pair(18.53500, 73.88000) // Off-Campus Proxy Attempt (~3.1 KM away)
            }

            val results = FloatArray(1)
            Location.distanceBetween(campusLat, campusLng, studentLat, studentLng, results)
            val distanceInMeters = results[0]
            val isOutOfRadius = distanceInMeters > 100.0f

            // Proxy fingerprint detection
            val isFingerprintConflict = simulatedDeviceSpoofState

            val recordStatus = when {
                isFingerprintConflict -> "Present" // Still logged but flagged of piracy
                isOutOfRadius && method == "GPS Radius Check" -> "Absent"
                else -> "Present"
            }

            // Flag trigger if Out Of Radius, Device Spoofed, or QR Token Mismatch
            val isFraudFlagged = isFingerprintConflict || (isOutOfRadius && method == "GPS Radius Check") || (providedQR != activeLecture.qrToken && method == "QR Code Rotation")

            val newRecord = AttendanceRecordEntity(
                studentId = student.id,
                studentName = student.name,
                batchName = student.batchName,
                subjectName = activeLecture.subjectName,
                status = recordStatus,
                method = method,
                latitude = studentLat,
                longitude = studentLng,
                markedAt = System.currentTimeMillis(),
                isFlagged = isFraudFlagged
            )

            repository.insertAttendanceRecord(newRecord)

            if (isFraudFlagged) {
                // Instantly generate active system safety notification
                val alertMsg = "AI Proxy Sentry flagged a suspicious access log from registration ${student.registrationNo} (Fingerprint Clash: $isFingerprintConflict, Radius Breached: $isOutOfRadius)"
                repository.sendBroadcast(
                    BroadcastAlertEntity(
                        title = "Proxy Sentry Triggered",
                        message = alertMsg,
                        targetBatch = "All Batches",
                        dateSent = "Today",
                        type = "AI Alert"
                    )
                )
                showToast("⚠️ Marked under Proxy Sentry Warning: Coordinates / Hardware ID misaligned.")
            } else {
                showToast("✅ Attendance Marked Successfully via $method!")
            }
            
            showMarkAttendanceDialog = false
            showQRScannerSimulation = false
        }
    }

    // STUDENT: Apply Leave Submission
    fun applyLeave(reason: String, dateFrom: String, dateTo: String, docType: String) {
        viewModelScope.launch {
            val student = database.studentDao().getStudentById(currentStudentId) ?: return@launch
            val leave = LeaveRequestEntity(
                studentId = student.id,
                studentName = student.name,
                batchName = student.batchName,
                dateFrom = dateFrom,
                dateTo = dateTo,
                reason = reason,
                docType = docType,
                status = "Pending"
            )
            repository.addLeaveRequest(leave)
            showToast("🛫 Leave Request for ${docType} submitted for review.")
            showApplyLeaveDialog = false
        }
    }

    // FACULTY: Start Class simulation
    fun toggleLectureState(lecture: LectureEntity) {
        viewModelScope.launch {
            if (lecture.status == "Upcoming") {
                // Close any currently Active classes
                val allLectures = database.lectureDao().getAllLectures().first()
                allLectures.filter { it.status == "Active" }.forEach {
                    repository.updateLecture(it.copy(status = "Closed"))
                }
                repository.updateLecture(lecture.copy(status = "Active", qrToken = _qrToken.value))
                showToast("📢 Started Lecture: ${lecture.subjectName}.")
            } else if (lecture.status == "Active") {
                repository.updateLecture(lecture.copy(status = "Closed"))
                showToast("🔒 Ended Lecture & logged remaining students as Absentees.")
                
                // Automatically send notification to the student defaulters in the batch
                repository.sendBroadcast(
                    BroadcastAlertEntity(
                        title = "Lecture Finished: Absentees Logged",
                        message = "Lecture on '${lecture.subjectName}' has concluded. Verification registers locked.",
                        targetBatch = lecture.batchName,
                        dateSent = "Just Now",
                        type = "Standard"
                    )
                )
            }
        }
    }

    // FACULTY: Action Leave Application
    fun resolveLeave(leave: LeaveRequestEntity, approved: Boolean) {
        viewModelScope.launch {
            val updatedStatus = if (approved) "Approved" else "Rejected"
            val updatedLeave = leave.copy(status = updatedStatus)
            repository.updateLeaveStatus(updatedLeave)
            
            // If approved, dynamically register their attendance record as "On Leave"
            if (approved) {
                repository.insertAttendanceRecord(
                    AttendanceRecordEntity(
                        studentId = leave.studentId,
                        studentName = leave.studentName,
                        batchName = leave.batchName,
                        subjectName = "Excused Leave Approved",
                        status = "On Leave",
                        method = "Manual Override",
                        latitude = 0.0,
                        longitude = 0.0,
                        markedAt = System.currentTimeMillis(),
                        isFlagged = false
                    )
                )
            }
            
            showToast("Leave request resolved: $updatedStatus.")
        }
    }

    // ADMIN: Broadcast alert notification
    fun sendAlert(title: String, message: String, batch: String, type: String) {
        viewModelScope.launch {
            repository.sendBroadcast(
                BroadcastAlertEntity(
                    title = title,
                    message = message,
                    targetBatch = batch,
                    dateSent = "Today",
                    type = type
                )
            )
            showToast("📣 Broadcast dispatched to batch $batch.")
            showSendBroadcastDialog = false
        }
    }

    // AI: Call Gemini 3.5 Flash for Monthly Report Narrative
    fun generateAIReportNarrative(batchName: String) {
        viewModelScope.launch {
            isGeneratingNarrative = true
            monthlyNarrativeOutput = "Reviewing historical database... Analyzing VFX student checkins... Generative Gemini engine compiling insights..."
            val students = database.studentDao().getAllStudents().first().filter { it.batchName == batchName || batchName == "All Batches" }
            val text = GeminiAiService.generateMonthlyReportNarrative(students, batchName)
            monthlyNarrativeOutput = text
            isGeneratingNarrative = false
        }
    }

    // AI: Call Gemini to Explain Student Risk
    fun explainStudentRisk(student: StudentEntity) {
        viewModelScope.launch {
            isGeneratingRiskExplanation = true
            explainedStudentName = student.name
            riskExplanationOutput = "Consulting Vertex AI predictive patterns, assessing consecutive absences and payment status..."
            val text = GeminiAiService.explainStudentRisk(student)
            riskExplanationOutput = text
            isGeneratingRiskExplanation = false
        }
    }

    private fun showToast(msg: String) {
        notificationToastMessage = msg
        viewModelScope.launch {
            delay(3500)
            if (notificationToastMessage == msg) {
                notificationToastMessage = null
            }
        }
    }
}


// --- MAIN ENTRY VIEW COMPOSABLE ---

@Composable
fun MainApp(viewModel: MainViewModel) {
    val students by viewModel.studentsList.collectAsState()
    val attendance by viewModel.attendanceRecords.collectAsState()
    val leaves by viewModel.leaveRequests.collectAsState()
    val lectures by viewModel.lecturesList.collectAsState()
    val alerts by viewModel.alertsList.collectAsState()

    val currentStudent = students.find { it.id == viewModel.currentStudentId }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(PinacBlackHex),
        topBar = {
            Column {
                // Header Bar (Custom Box instead of SmallTopAppBar to prevent unstable experimental M3 APIs)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PinacDarkCardHex)
                        .statusBarsPadding()
                        .border(width = 1.dp, color = PinacDarkCardLightHex)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Column {
                            Text(
                                text = "PINAC INSTITUTE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MutedText,
                                letterSpacing = 2.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Smart Attend",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = WhiteText,
                                    letterSpacing = (-0.5).sp
                                )
                                Text(
                                    text = ".",
                                    fontSize = 22.sp,
                                    color = PinacYellowAccent,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                    
                    // Offline Status Sync Simulation Badge & User Initials Avatar on Right
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // SQL Sync badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(CleanGreen.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .border(1.dp, CleanGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(CleanGreen)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SQL SYNCED", color = CleanGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }

                        val initials = when (viewModel.activeRole) {
                            "STUDENT" -> "PG"
                            "FACULTY" -> "MJ"
                            else -> "SA"
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PinacYellowAccent)
                                .border(2.dp, DeepSlate900, CircleShape)
                        ) {
                            Text(
                                text = initials,
                                color = DeepSlate900,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // High-End Interactive Role Switcher Hub
                Card(
                    colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, PinacDarkCardLightHex)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "DEVELOPER DEMO HUB: OVERRIDE SYSTEM USER PERSONA",
                            color = DeepSlate900,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf("STUDENT", "FACULTY", "ADMIN").forEach { role ->
                                val selected = viewModel.activeRole == role
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("role_select_${role.lowercase()}")
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) PinacYellowAccent else PinacDarkCardLightHex)
                                        .border(
                                            width = if (selected) 2.dp else 0.dp,
                                            color = if (selected) DeepSlate900 else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            viewModel.activeRole = role
                                            viewModel.selectedTab = 0 // Return to home on shift
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        text = role,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        color = DeepSlate900
                                    )
                                }
                            }
                        }
                        
                        // Active Simulated Account Indicator
                        if (viewModel.activeRole == "STUDENT" && currentStudent != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Simulating User: ${currentStudent.name} (${currentStudent.batchName})",
                                    color = MutedText,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "Change login",
                                    color = BorderAlertYellow,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        val currentIndex = students.indexOfFirst { it.id == viewModel.currentStudentId }
                                        val nextIndex = (currentIndex + 1) % students.size
                                        viewModel.currentStudentId = students[nextIndex].id
                                    }
                                )
                            }
                        } else if (viewModel.activeRole == "FACULTY") {
                            Text(
                                text = "Faculty Instructor: Prof. Milind Jadhav (Digital Effects Division)",
                                color = MutedText,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(top = 6.dp)
                            )
                        } else {
                            Text(
                                text = "Super Administrator console (Pinac Institute of Animation, Mumbai)",
                                color = MutedText,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 4.dp).padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            val navItems = when (viewModel.activeRole) {
                "STUDENT" -> listOf(
                    Triple("Home", Icons.Filled.Home, 0),
                    Triple("Invoices", Icons.Filled.Payments, 2),
                    Triple("Calendar", Icons.Filled.DateRange, 3)
                )
                "FACULTY" -> listOf(
                    Triple("Registry", Icons.Filled.Home, 0),
                    Triple("Approvals", Icons.Filled.CheckCircle, 2),
                    Triple("Lectures", Icons.Filled.Book, 3)
                )
                else -> listOf( // ADMIN
                    Triple("Analytics", Icons.Filled.Home, 0),
                    Triple("AI Summary", Icons.Filled.Analytics, 1),
                    Triple("Alerts", Icons.Filled.Notifications, 2),
                    Triple("Audit Logs", Icons.Filled.Security, 3)
                )
            }

            NavigationBar(
                containerColor = PinacDarkCardHex,
                modifier = Modifier
                    .navigationBarsPadding()
                    .border(width = 1.dp, color = PinacDarkCardLightHex),
                tonalElevation = 0.dp
            ) {
                navItems.forEach { (label, icon, index) ->
                    val active = viewModel.selectedTab == index
                    NavigationBarItem(
                        selected = active,
                        onClick = { viewModel.selectedTab = index },
                        icon = { Icon(imageVector = icon, contentDescription = label, tint = if (active) DeepSlate900 else MutedText) },
                        label = { Text(text = label, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (active) DeepSlate900 else MutedText) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DeepSlate900,
                            selectedTextColor = DeepSlate900,
                            indicatorColor = PinacYellowAccent,
                            unselectedIconColor = MutedText,
                            unselectedTextColor = MutedText
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PinacBlackHex)
        ) {
            // Screen Dispatcher based on chosen Role and tab index
            AnimatedContent(
                targetState = Pair(viewModel.activeRole, viewModel.selectedTab),
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                }
            ) { (role, tab) ->
                when (role) {
                    "STUDENT" -> {
                        when (tab) {
                            0 -> StudentHomeScreen(viewModel, currentStudent, lectures, attendance)
                            2 -> StudentInvoicesScreen(currentStudent)
                            3 -> StudentCalendarScreen(viewModel, attendance, currentStudent)
                            else -> StudentHomeScreen(viewModel, currentStudent, lectures, attendance)
                        }
                    }
                    "FACULTY" -> {
                        when (tab) {
                            0 -> FacultyHomeScreen(viewModel, lectures, students, attendance)
                            2 -> FacultyApprovalsScreen(viewModel, leaves)
                            3 -> FacultyLecturesScreen(viewModel, lectures)
                            else -> FacultyHomeScreen(viewModel, lectures, students, attendance)
                        }
                    }
                    "ADMIN" -> {
                        when (tab) {
                            0 -> AdminHomeScreen(viewModel, students, attendance)
                            1 -> AdminAiReportScreen(viewModel)
                            2 -> AdminBroadcastsScreen(viewModel, alerts)
                            3 -> AdminAuditLogsScreen(attendance, students)
                            else -> AdminHomeScreen(viewModel, students, attendance)
                        }
                    }
                }
            }

            // Real-time toast alerts floating layer
            viewModel.notificationToastMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = PinacYellowAccent),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .fillMaxWidth(0.9f)
                        .border(1.dp, PinacYellowAccentLight, RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = null,
                            tint = PinacBlackHex,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = msg,
                            color = PinacBlackHex,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // --- ALL DIALOG PORTATIVE OVERLAYS ---

            if (viewModel.showMarkAttendanceDialog) {
                MarkAttendanceOverlay(viewModel, lectures.find { it.status == "Active" })
            }

            if (viewModel.showApplyLeaveDialog) {
                ApplyLeaveOverlay(viewModel)
            }

            if (viewModel.showSendBroadcastDialog) {
                SendBroadcastOverlay(viewModel)
            }

            // AI Risk Evaluation modal
            if (viewModel.isGeneratingRiskExplanation || viewModel.riskExplanationOutput.isNotEmpty()) {
                Dialog(onDismissRequest = { viewModel.riskExplanationOutput = "" }) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, PinacYellowAccent, RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Analytics,
                                    contentDescription = null,
                                    tint = PinacYellowAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "VERTEX AI RISK EXPLANATION",
                                    color = WhiteText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Analysis Target: ${viewModel.explainedStudentName}",
                                color = PinacYellowAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            if (viewModel.isGeneratingRiskExplanation) {
                                CircularProgressIndicator(color = PinacYellowAccent, modifier = Modifier.align(Alignment.CenterHorizontally))
                            } else {
                                Text(
                                    text = viewModel.riskExplanationOutput,
                                    color = WhiteText,
                                    fontSize = 13.sp,
                                    lineHeight = 20.sp
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { viewModel.riskExplanationOutput = "" },
                                colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                                modifier = Modifier.align(Alignment.End),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("CLOSE", color = PinacBlackHex, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // QR Rotational Generator Dialog (Expanded modal detail)
            if (viewModel.showQRExpandingCodeDialog) {
                val liveToken by viewModel.qrToken.collectAsState()
                val liveTimer by viewModel.qrTimerRemaining.collectAsState()
                Dialog(onDismissRequest = { viewModel.showQRExpandingCodeDialog = false }) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PinacBlackHex),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, PinacYellowAccent, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                "ROTATING QR ENGINE (V1.0)",
                                color = PinacYellowAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Screenshot Block Active · Refresh TTL: 30s",
                                color = MutedText,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            // Mock QR Code Render
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(200.dp)
                                    .background(WhiteText, RoundedCornerShape(12.dp))
                                    .padding(8.dp)
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    // Let us draw stylized graphic QR square nodes represent the crypto token
                                    val tokenHash = liveToken.hashCode()
                                    val columns = 12
                                    var cellWidth = size.width / columns
                                    for (x in 0 until columns) {
                                        for (y in 0 until columns) {
                                            val bit = ((tokenHash xor (x * 43) xor (y * 97)) % 3 == 0)
                                            if (bit) {
                                                drawRect(
                                                    color = Color.Black,
                                                    topLeft = androidx.compose.ui.geometry.Offset(x * cellWidth, y * cellWidth),
                                                    size = androidx.compose.ui.geometry.Size(cellWidth - 1, cellWidth - 1)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // HMAC String indicator
                            Card(
                                colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = liveToken,
                                    color = PinacYellowAccent,
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(20.dp))
                            
                            // Dynamic Countdown Timer slide mapping
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .height(4.dp)
                                        .weight(1f)
                                        .background(MutedText.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(liveTimer / 30.0f)
                                            .background(PinacYellowAccent, RoundedCornerShape(2.dp))
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "${liveTimer}s remaining",
                                    color = WhiteText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { viewModel.showQRExpandingCodeDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("CLOSE ATTENDENCE WINDOW", color = PinacBlackHex, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}


// --- SCREEN: STUDENT WORKFOLIO HOME ---

@Composable
fun StudentHomeScreen(
    viewModel: MainViewModel,
    student: StudentEntity?,
    lectures: List<LectureEntity>,
    attendanceList: List<AttendanceRecordEntity>
) {
    if (student == null) return

    val currentActiveClass = lectures.find { it.status == "Active" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming card (Styled exact to Geometric Theme: Charcoal/Slate-900 with Yellow Highlights and Streak)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DeepSlate900),
                shape = RoundedCornerShape(26.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CURRENT ATTENDANCE", color = CleanSlate400, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${student.attendanceRate.toInt()}", color = SoftWhite, fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                                Text("%", color = PinacYellowAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                            }
                            Text(student.name, color = SoftWhite.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Course: UI Designer & VFX VFX-2025", color = PinacYellowAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                        
                        // High contrast badge state
                        Box(
                            modifier = Modifier
                                .background(PinacYellowAccent.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .border(1.dp, PinacYellowAccent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "ON TRACK", 
                                color = PinacYellowAccent, 
                                fontSize = 10.sp, 
                                fontWeight = FontWeight.ExtraBold, 
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Stylized Geometric Streaks
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val activeBarsCount = (student.attendanceRate / 25).toInt()
                        for (i in 0 until 4) {
                            val isActive = i < activeBarsCount
                            Box(
                                modifier = Modifier
                                    .height(4.dp)
                                    .weight(1f)
                                    .background(
                                        color = if (isActive) PinacYellowAccent else Color(0xFF334155),
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "24 LECTURES ATTENDED THIS SEMESTER", 
                        color = CleanSlate400, 
                        fontSize = 9.sp, 
                        fontWeight = FontWeight.Bold, 
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // Active Class / Check-in Notice Widgets
        item {
            if (currentActiveClass != null) {
                val hasAlreadyMarked = attendanceList.any { it.studentId == student.id && it.markedAt > System.currentTimeMillis() - 4 * 60 * 60 * 1000L }
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(26.dp))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(PinacYellowAccent)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(currentActiveClass.subjectName, color = WhiteText, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = (-0.5).sp)
                                Text("Batch: ${currentActiveClass.batchName} · Time: ${currentActiveClass.timeStart} - ${currentActiveClass.timeEnd}", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(18.dp))
                        
                        // Verification UI Block from mockup
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(16.dp))
                                .background(PinacBlackHex)
                                .padding(vertical = 20.dp, horizontal = 12.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .border(3.dp, PinacYellowAccent, RoundedCornerShape(16.dp))
                                        .background(PinacDarkCardHex)
                                        .padding(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.QrCode,
                                        contentDescription = "QR Live Checkin",
                                        tint = WhiteText,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "RADIUS LOCK WORKING",
                                    color = CleanGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Verification lock verified within 100m limit",
                                    color = MutedText,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (!hasAlreadyMarked) {
                            Button(
                                onClick = { viewModel.showMarkAttendanceDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .testTag("mark_attendance_trigger")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Filled.QrCodeScanner, contentDescription = null, tint = DeepSlate900)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("VERIFY ATTENDENCE", color = DeepSlate900, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CleanGreen.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                    .border(1.dp, CleanGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Filled.Verified, contentDescription = null, tint = CleanGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("ATTENDANCE APPROVED TODAY", color = CleanGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(18.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Filled.Info, contentDescription = null, tint = MutedText)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("No Active Lecture", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Next schedule starts at 12:00 PM today.", color = MutedText, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Leave Requests Action Cards
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(18.dp))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("LEAVE APPLICATIVE HUB", color = WhiteText, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 0.5.sp)
                        IconButton(
                            onClick = { viewModel.showApplyLeaveDialog = true },
                            modifier = Modifier.size(24.dp).testTag("apply_leave_trigger")
                        ) {
                            Icon(imageVector = Icons.Filled.AddCircle, contentDescription = null, tint = PinacYellowAccent, modifier = Modifier.size(24.dp))
                        }
                    }
                    Text("Need to apply medical / general leave registers? Click the + button above.", color = MutedText, fontSize = 11.sp)
                }
            }
        }

        // Fee Warnings Correlation card
        item {
            if (student.feeStatus == "Due" || student.feeStatus == "Overdue") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = RiskRed.copy(alpha = 0.1f)),
                    modifier = Modifier.border(1.dp, RiskRed, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = RiskRed)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("FINANCIAL BALANCE WARNING", color = RiskRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Semester 2 fees of INR ${student.feeAmount.toInt()} are currently [${student.feeStatus}]. System warning: Non-payment may trigger automated attendance-slip locks by next week.",
                            color = WhiteText,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}


// --- SCREEN: STUDENT WORKFOLIO INVOICES ---

@Composable
fun StudentInvoicesScreen(student: StudentEntity?) {
    if (student == null) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("FEE INVOICE HUB", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
        Text("Correlation between payment schedules and platform compliance", color = MutedText, fontSize = 11.sp)
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Invoice No.", color = MutedText, fontSize = 11.sp)
                    Text("Amount due", color = MutedText, fontSize = 11.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("PINAC-INV2026-A9", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("INR ${student.feeAmount.toInt()}", color = if (student.feeStatus == "Paid") CleanGreen else RiskRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = PinacDarkCardLightHex)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("STATUS", color = MutedText, fontSize = 10.sp)
                        Text(student.feeStatus, color = if (student.feeStatus == "Paid") CleanGreen else RiskRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Column {
                        Text("DUE BY", color = MutedText, fontSize = 10.sp)
                        Text("15 Jun 2026", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}


// --- SCREEN: STUDENT CALENDAR HISTORY ---

@Composable
fun StudentCalendarScreen(
    viewModel: MainViewModel,
    attendanceList: List<AttendanceRecordEntity>,
    student: StudentEntity?
) {
    if (student == null) return

    val personalRecords = attendanceList.filter { it.studentId == student.id }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("CLASS REGISTRY CALENDAR", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
        Text("Interactive monthly logging grid", color = MutedText, fontSize = 11.sp)
        
        Spacer(modifier = Modifier.height(16.dp))

        // Month Indicator Card
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("MAY 2026", color = PinacYellowAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${personalRecords.filter { it.status == "Present" }.size} Present · ${personalRecords.filter { it.status == "Absent" }.size} Absent", color = WhiteText, fontSize = 12.sp)
            }
        }

        // Mock 31-day Calender Layout with color mappings
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            items(daysOfWeek) {
                Text(it, color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }

            // Fill empty cells up to our initial simulated start (Assume Month starts on Thursday)
            items(3) {
                Box(modifier = Modifier.aspectRatio(1f))
            }

            // Calendar slots 1 to 31
            items(31) { day ->
                val dayNum = day + 1
                
                // Fetch simulated record status mapping
                val dayRecord = when (dayNum) {
                    22 -> personalRecords.find { it.markedAt > 0 && it.status == "Present" } // mock historical alignment
                    23 -> personalRecords.find { it.status == "On Leave" }
                    24 -> personalRecords.find { it.status == "Absent" }
                    25 -> personalRecords.find { it.status == "Present" }
                    else -> null
                }

                val statusColor = when (dayRecord?.status) {
                    "Present" -> CleanGreen
                    "Absent" -> RiskRed
                    "On Leave" -> BorderAlertYellow
                    else -> HolidayGrey.copy(alpha = 0.5f)
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor)
                ) {
                    Text(
                        dayNum.toString(),
                        color = if (dayRecord != null) WhiteText else WhiteText.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}


// --- SCREEN: FACULTY INSTRUCTOR HOME ---

@Composable
fun FacultyHomeScreen(
    viewModel: MainViewModel,
    lectures: List<LectureEntity>,
    students: List<StudentEntity>,
    attendanceRecords: List<AttendanceRecordEntity>
) {
    val activeClass = lectures.find { it.status == "Active" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming card
        item {
            Card(colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("FACULTY CONTROL PANEL", color = PinacYellowAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Milind Jadhav", color = WhiteText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Digital FX & Compositing Department", color = MutedText, fontSize = 12.sp)
                }
            }
        }

        // Action Lecture start blocks
        item {
            Text("LIVESTREAM TIMETABLE & QR LOCKS", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        items(lectures) { lec ->
            val isActive = lec.status == "Active"
            val isClosed = lec.status == "Closed"

            Card(
                colors = CardDefaults.cardColors(containerColor = if (isActive) PinacYellowAccent.copy(alpha = 0.08f) else PinacDarkCardHex),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isActive) PinacYellowAccent else PinacDarkCardLightHex,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(lec.subjectName, color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when (lec.status) {
                                        "Active" -> CleanGreen
                                        "Closed" -> HolidayGrey
                                        else -> BorderAlertYellow.copy(alpha = 0.2f)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                lec.status,
                                fontSize = 9.sp,
                                color = if (lec.status == "Active") WhiteText else PinacYellowAccent,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                    Text("Batch: ${lec.batchName} · Time: ${lec.timeStart} - ${lec.timeEnd}", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (isActive) {
                        val classRecords = attendanceRecords.filter { it.subjectName == lec.subjectName }
                        Text("Live Checkins: ${classRecords.size} students validated", color = CleanGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.showQRExpandingCodeDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("faculty_show_qr")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.QrCode, contentDescription = null, tint = PinacBlackHex, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("SHOW PROTATING QR", color = PinacBlackHex, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Button(
                                onClick = { viewModel.toggleLectureState(lec) },
                                colors = ButtonDefaults.buttonColors(containerColor = RiskRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).testTag("faculty_stop_lecture")
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Cancel, contentDescription = null, tint = WhiteText, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("CLOSE CLASS & LOCK", color = WhiteText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (isClosed) {
                        Text("Class locked. Attendance compiled cleanly.", color = MutedText, fontSize = 12.sp)
                    } else {
                        Button(
                            onClick = { viewModel.toggleLectureState(lec) },
                            colors = ButtonDefaults.buttonColors(containerColor = PinacDarkCardLightHex),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("faculty_start_lecture_${lec.id}")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = PinacYellowAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("OPEN GPS LOCK & QR SLOT", color = PinacYellowAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}


// --- SCREEN: FACULTY LEAVE APPROVALS ---

@Composable
fun FacultyApprovalsScreen(viewModel: MainViewModel, leaves: List<LeaveRequestEntity>) {
    val pendingLeaves = leaves.filter { it.status == "Pending" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("LEAVE SIGN-OFF REQUESTS", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
                Text("Verify official medical documentation of absences", color = MutedText, fontSize = 11.sp)
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(PinacYellowAccent)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${pendingLeaves.size} PENDING",
                    color = PinacBlackHex,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (pendingLeaves.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = CleanGreen, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Roster Clean", color = WhiteText, fontWeight = FontWeight.Bold)
                    Text("All student leave logs reviewed.", color = MutedText, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pendingLeaves) { leave ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                        modifier = Modifier.fillMaxWidth().border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(leave.studentName, color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(leave.batchName, color = PinacYellowAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                "Duration: ${leave.dateFrom} to ${leave.dateTo}",
                                color = MutedText,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Reason: \"${leave.reason}\"",
                                color = WhiteText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null, tint = PinacYellowAccent, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Attachment: ${leave.docType}", color = PinacYellowAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.resolveLeave(leave, approved = false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = PinacDarkCardLightHex),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).testTag("leave_reject_${leave.id}")
                                ) {
                                    Text("REJECT", color = RiskRed, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { viewModel.resolveLeave(leave, approved = true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CleanGreen),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).testTag("leave_approve_${leave.id}")
                                ) {
                                    Text("APPROVE SIGN-OFF", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// --- SCREEN: FACULTY LECTURES LIST ---

@Composable
fun FacultyLecturesScreen(viewModel: MainViewModel, lectures: List<LectureEntity>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("TIMETABLE CLASS REGISTRY", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
        Text("Today's schedules blocks inside Pinac", color = MutedText, fontSize = 11.sp)
        
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(lectures) { lec ->
                Card(colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Book,
                            contentDescription = null,
                            tint = PinacYellowAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(lec.subjectName, color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Time: ${lec.timeStart} - ${lec.timeEnd} · Status: [${lec.status}]", color = MutedText, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}


// --- SCREEN: ADMIN STRATEGIC DASHBOARD ---

@Composable
fun AdminHomeScreen(
    viewModel: MainViewModel,
    students: List<StudentEntity>,
    attendanceRecords: List<AttendanceRecordEntity>
) {
    var filterQuery by remember { mutableStateOf("") }
    var defaulterPercentageThreshold by remember { mutableStateOf(75f) }

    val filteredStudents = students.filter {
        it.name.contains(filterQuery, ignoreCase = true) || it.batchName.contains(filterQuery, ignoreCase = true)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming overview
        item {
            Card(colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ACADEMIC ADMINISTRATION BOARD", color = PinacYellowAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("Pinac Institute Director Console", color = WhiteText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Live check-in KPIs
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TOTAL ENROLLED", color = MutedText, fontSize = 10.sp)
                            Text("${students.size}", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SYSTEM DEFAULTERS", color = MutedText, fontSize = 10.sp)
                            Text("${students.count { it.attendanceRate < defaulterPercentageThreshold }}", color = RiskRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("PROXIES FLAGGED", color = MutedText, fontSize = 10.sp)
                            Text("${attendanceRecords.count { it.isFlagged }}", color = BorderAlertYellow, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // Defaulter Threshold Slider Configurator (Section 15: Admin Controls)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                modifier = Modifier.fillMaxWidth().border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "DEFAULTER RATIO SYSTEM REGULATOR",
                        color = PinacYellowAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Set threshold ratio. Students holding ratings below are cataloged into the AI escalation lists.",
                        color = MutedText,
                        fontSize = 11.sp
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = defaulterPercentageThreshold,
                            onValueChange = { defaulterPercentageThreshold = it },
                            valueRange = 50f..95f,
                            colors = SliderDefaults.colors(
                                thumbColor = PinacYellowAccent,
                                activeTrackColor = PinacYellowAccent,
                                inactiveTrackColor = PinacDarkCardLightHex
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "${defaulterPercentageThreshold.toInt()}%",
                            color = WhiteText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Student Roster
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("STUDENT ROSTER & RISK WATCH", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                
                // Trigger Send Broadcast Dialog (Section 15)
                Row(
                    modifier = Modifier
                        .clickable { viewModel.showSendBroadcastDialog = true }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Campaign, contentDescription = null, tint = PinacYellowAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("BROADCAST ALERT", color = PinacYellowAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }

        item {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                label = { Text("Search by student / Batch Name", color = MutedText) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = WhiteText,
                    unfocusedTextColor = WhiteText,
                    focusedBorderColor = PinacYellowAccent,
                    unfocusedBorderColor = PinacDarkCardLightHex,
                    cursorColor = PinacYellowAccent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MutedText) }
            )
        }

        items(filteredStudents) { std ->
            val isDefaulter = std.attendanceRate < defaulterPercentageThreshold

            Card(
                colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isDefaulter) RiskRed.copy(alpha = 0.5f) else PinacDarkCardLightHex,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(std.name, color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            // Risk score tag
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when (std.riskLevel) {
                                            "High" -> RiskRed
                                            "Medium" -> BorderAlertYellow
                                            else -> CleanGreen
                                        }
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    "${std.riskLevel} Risk",
                                    fontSize = 8.sp,
                                    color = if (std.riskLevel == "Medium") PinacBlackHex else WhiteText,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text("${std.registrationNo} · ${std.batchName}", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                        
                        // Device bind indicator
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = MutedText, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Device ID: ${std.deviceFingerprint}", color = MutedText, fontSize = 9.sp)
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${"%.1f".format(std.attendanceRate)}%",
                            color = if (isDefaulter) RiskRed else CleanGreen,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "AI Predictor Action",
                            color = PinacYellowAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { viewModel.explainStudentRisk(std) }
                                .background(PinacYellowAccent.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}


// --- SCREEN: ADMIN AI REPORT GENERATION NARRATIVE (Section 10) ---

@Composable
fun AdminAiReportScreen(viewModel: MainViewModel) {
    var selectedReportBatch by remember { mutableStateOf("All Batches") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacYellowAccent.copy(alpha = 0.08f)),
            modifier = Modifier.border(1.dp, PinacYellowAccent, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = PinacYellowAccent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GEMINI ALGORITHMIC INSIGHT ANALYTICA", color = PinacYellowAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Leverage direct Gemini 3.5 Flash server integrations to run advanced, natural-language monthly correlations. Generates predictive graphs analysis on VFX submissions instantly.",
                    color = WhiteText,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selector buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf("All Batches", "VFX 2025-A", "3D Animation 2025-B").forEach { batch ->
                val active = selectedReportBatch == batch
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) PinacYellowAccent else PinacDarkCardHex)
                        .clickable { selectedReportBatch = batch }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = batch,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) PinacBlackHex else WhiteText
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.generateAIReportNarrative(selectedReportBatch) },
            colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("generate_ai_narrative")
        ) {
            if (viewModel.isGeneratingNarrative) {
                CircularProgressIndicator(color = PinacBlackHex, modifier = Modifier.size(20.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = PinacBlackHex, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GENERATE MONTHLY NARRATIVE", color = PinacBlackHex, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Narrative Display card
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, PinacDarkCardLightHex, RoundedCornerShape(16.dp))
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                if (viewModel.monthlyNarrativeOutput.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(Icons.Filled.Book, contentDescription = null, tint = MutedText, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No Report Generated", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Click the button above to run the AI engine.", color = MutedText, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = viewModel.monthlyNarrativeOutput,
                                color = WhiteText,
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


// --- SCREEN: ADMIN BROADCASTS MANAGER (Section 12) ---

@Composable
fun AdminBroadcastsScreen(viewModel: MainViewModel, alerts: List<BroadcastAlertEntity>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("INSTITUTIONAL BROADCAST COMPLIANCE", color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Logs of active broadcast messages and alerts", color = MutedText, fontSize = 11.sp)
            }
            IconButton(
                onClick = { viewModel.showSendBroadcastDialog = true },
                modifier = Modifier.testTag("admin_new_broadcast")
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = PinacYellowAccent)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(alerts) { alert ->
                val typeColor = when (alert.type) {
                    "Emergency" -> RiskRed
                    "Fee Warning" -> BorderAlertYellow
                    "AI Alert" -> PinacYellowAccent
                    else -> MutedText
                }

                Card(colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = alert.type.uppercase(),
                                color = typeColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp
                            )
                            Text(alert.dateSent, color = MutedText, fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(alert.title, color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(alert.message, color = WhiteText.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 18.sp)
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider(color = PinacDarkCardLightHex)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Target: ${alert.targetBatch}", color = PinacYellowAccent, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}


// --- SCREEN: AUDIT SYSTEM EVENT VIEWER (Section 11) ---

@Composable
fun AdminAuditLogsScreen(attendance: List<AttendanceRecordEntity>, students: List<StudentEntity>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ADMIN SECURITY EVENT VIEWER", color = PinacYellowAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                Text("Live logging telemetry tracking hardware IP and potential GPS cheats.", color = MutedText, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val flaggedRecords = attendance.filter { it.isFlagged }

        if (flaggedRecords.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Security, contentDescription = null, tint = CleanGreen, modifier = Modifier.size(36.dp))
                    Text("No Security Breach Flagged", color = WhiteText, fontWeight = FontWeight.Bold)
                    Text("Device fingerprints align safely.", color = MutedText, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(flaggedRecords) { rec ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PinacDarkCardHex),
                        modifier = Modifier.border(1.dp, RiskRed.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(rec.studentName, color = WhiteText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("PROXY WARN", color = RiskRed, fontWeight = FontWeight.Black, fontSize = 10.sp)
                            }
                            Text("Batch Program: ${rec.batchName} · Method: ${rec.method}", color = MutedText, fontSize = 11.sp)
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "IP Signature: 192.168.12.${(10..254).random()} · Coordinates: Lat ${rec.latitude}, Lng ${rec.longitude}",
                                color = WhiteText,
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cause: Checked-in at ~3.1 km away or hardware mismatch mismatching registration databases.",
                                color = BorderAlertYellow,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}


// --- ATTACHED DIALOGS AND OVERLAYS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkAttendanceOverlay(viewModel: MainViewModel, activeLecture: LectureEntity?) {
    var providedToken by remember { mutableStateOf("") }

    Dialog(onDismissRequest = { viewModel.showMarkAttendanceDialog = false }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacBlackHex),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, PinacYellowAccent, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "VALIDATE LECTURE CHECK-IN",
                    color = PinacYellowAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "Class: ${activeLecture?.subjectName ?: "None"}",
                    color = WhiteText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // GPS Simulation toggle switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PinacDarkCardHex, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Coordinate Radius Lock",
                            color = WhiteText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (viewModel.simulatedIsOnCampus) "Simulated: On-Campus (5m, fits lock)" else "Simulated: Off-Campus / Proxy Cheat (3.1km, out)",
                            color = if (viewModel.simulatedIsOnCampus) CleanGreen else RiskRed,
                            fontSize = 11.sp,
                            modifier = Modifier.testTag("gps_status_label")
                        )
                    }
                    Switch(
                        checked = viewModel.simulatedIsOnCampus,
                        onCheckedChange = { viewModel.simulatedIsOnCampus = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PinacYellowAccent,
                            checkedTrackColor = PinacYellowAccent.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.testTag("gps_toggle_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Duplicate binding test toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PinacDarkCardHex, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Device Binding Simulation",
                            color = WhiteText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (viewModel.simulatedDeviceSpoofState) "Cheating! IP footprint shared with std02" else "Safe hardware registration verified",
                            color = if (viewModel.simulatedDeviceSpoofState) RiskRed else CleanGreen,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = viewModel.simulatedDeviceSpoofState,
                        onCheckedChange = { viewModel.simulatedDeviceSpoofState = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PinacYellowAccent,
                            checkedTrackColor = PinacYellowAccent.copy(alpha = 0.5f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Option 1: Mark Attendance by Coordinate Radius
                Button(
                    onClick = { viewModel.simulateMarkAttendance("GPS Radius Check", "") },
                    colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                    modifier = Modifier.fillMaxWidth().testTag("mark_gps_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null, tint = PinacBlackHex, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("VERIFY GPS & SUBMIT", color = PinacBlackHex, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                Divider(color = PinacDarkCardLightHex)
                Spacer(modifier = Modifier.height(12.dp))

                Text("Option 2: ROTATIONAL QR TOKEN MATCH", color = MutedText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                
                OutlinedTextField(
                    value = providedToken,
                    onValueChange = { providedToken = it },
                    placeholder = { Text("Enter live QR token string", color = MutedText, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().testTag("qr_token_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WhiteText,
                        unfocusedTextColor = WhiteText,
                        focusedBorderColor = PinacYellowAccent,
                        unfocusedBorderColor = PinacDarkCardLightHex
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = { viewModel.simulateMarkAttendance("QR Code Rotation", providedToken) },
                    colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                    modifier = Modifier.fillMaxWidth().testTag("submit_qr_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.QrCode, contentDescription = null, tint = PinacBlackHex, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SCAN & VALIDATE QR TOKEN", color = PinacBlackHex, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = { viewModel.showMarkAttendanceDialog = false },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("CANCEL", color = PinacYellowAccent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplyLeaveOverlay(viewModel: MainViewModel) {
    var reason by remember { mutableStateOf("") }
    var selectionFrom by remember { mutableStateOf("2026-05-27") }
    var selectionTo by remember { mutableStateOf("2026-05-28") }
    var docSelection by remember { mutableStateOf("Medical Certificate.pdf") }

    Dialog(onDismissRequest = { viewModel.showApplyLeaveDialog = false }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacBlackHex),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, PinacYellowAccent, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "LEAVE APPLICATION FORM",
                    color = PinacYellowAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                // Reason input
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Detailed Reason of Absence", color = MutedText) },
                    modifier = Modifier.fillMaxWidth().testTag("leave_reason_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WhiteText,
                        unfocusedTextColor = WhiteText,
                        focusedBorderColor = PinacYellowAccent,
                        unfocusedBorderColor = PinacDarkCardLightHex
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // From/To Dates details
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = selectionFrom,
                        onValueChange = { selectionFrom = it },
                        label = { Text("From Date", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = WhiteText,
                            unfocusedTextColor = WhiteText,
                            focusedBorderColor = PinacYellowAccent,
                            unfocusedBorderColor = PinacDarkCardLightHex
                        )
                    )
                    OutlinedTextField(
                        value = selectionTo,
                        onValueChange = { selectionTo = it },
                        label = { Text("To Date", color = MutedText) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = WhiteText,
                            unfocusedTextColor = WhiteText,
                            focusedBorderColor = PinacYellowAccent,
                            unfocusedBorderColor = PinacDarkCardLightHex
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Simulated document attachment selection
                Text("Simulated Document Upload:", color = WhiteText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    listOf("Medical Certificate.pdf", "Personal Slip.jpg").forEach { item ->
                        val selected = docSelection == item
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) PinacYellowAccent else PinacDarkCardHex)
                                .clickable { docSelection = item }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                item,
                                color = if (selected) PinacBlackHex else WhiteText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.showApplyLeaveDialog = false }) {
                        Text("CANCEL", color = WhiteText)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { viewModel.applyLeave(reason, selectionFrom, selectionTo, docSelection) },
                        colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                        shape = RoundedCornerShape(8.dp),
                        enabled = reason.isNotEmpty(),
                        modifier = Modifier.testTag("leave_submit_button")
                    ) {
                        Text("SUBMIT APPLICATION", color = PinacBlackHex, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendBroadcastOverlay(viewModel: MainViewModel) {
    var textTitle by remember { mutableStateOf("") }
    var textMsg by remember { mutableStateOf("") }
    var batchSelection by remember { mutableStateOf("All Batches") }
    var typeSelection by remember { mutableStateOf("Standard") }

    Dialog(onDismissRequest = { viewModel.showSendBroadcastDialog = false }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = PinacBlackHex),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, PinacYellowAccent, RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "DISPATCH SYSTEM-WIDE BROADCAST",
                    color = PinacYellowAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Title input
                OutlinedTextField(
                    value = textTitle,
                    onValueChange = { textTitle = it },
                    label = { Text("Announcement Title", color = MutedText) },
                    modifier = Modifier.fillMaxWidth().testTag("broadcast_title_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WhiteText,
                        unfocusedTextColor = WhiteText,
                        focusedBorderColor = PinacYellowAccent,
                        unfocusedBorderColor = PinacDarkCardLightHex
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // message input
                OutlinedTextField(
                    value = textMsg,
                    onValueChange = { textMsg = it },
                    label = { Text("Detailed description message", color = MutedText) },
                    modifier = Modifier.fillMaxWidth().testTag("broadcast_message_input"),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = WhiteText,
                        unfocusedTextColor = WhiteText,
                        focusedBorderColor = PinacYellowAccent,
                        unfocusedBorderColor = PinacDarkCardLightHex
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Target batch selector
                Text("Target Batch Program:", color = WhiteText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    listOf("All Batches", "VFX 2025-A", "3D Animation 2025-B").forEach { batch ->
                        val selected = batchSelection == batch
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) PinacYellowAccent else PinacDarkCardHex)
                                .clickable { batchSelection = batch }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                batch,
                                color = if (selected) PinacBlackHex else WhiteText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Danger classification Type selector
                Text("Broadcast Classification:", color = WhiteText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    listOf("Standard", "Emergency", "Fee Warning").forEach { type ->
                        val selected = typeSelection == type
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) PinacYellowAccent else PinacDarkCardHex)
                                .clickable { typeSelection = type }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                type,
                                color = if (selected) PinacBlackHex else WhiteText,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { viewModel.showSendBroadcastDialog = false }) {
                        Text("CANCEL", color = WhiteText)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { viewModel.sendAlert(textTitle, textMsg, batchSelection, typeSelection) },
                        colors = ButtonDefaults.buttonColors(containerColor = PinacYellowAccent),
                        shape = RoundedCornerShape(8.dp),
                        enabled = textTitle.isNotEmpty() && textMsg.isNotEmpty(),
                        modifier = Modifier.testTag("broadcast_submit_button")
                    ) {
                        Text("DISPATCH BROADCAST", color = PinacBlackHex, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
