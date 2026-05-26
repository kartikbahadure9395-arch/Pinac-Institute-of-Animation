package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// --- ROOM ENTITIES ---

@Entity(tableName = "students")
data class StudentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val email: String,
    val batchName: String,
    val attendanceRate: Float, // e.g. 78.5f for 78.5%
    val feeStatus: String, // "Paid", "Due", "Overdue"
    val feeAmount: Double,
    val registrationNo: String,
    val riskScore: Float, // 0.0 to 1.0 AI score
    val riskLevel: String, // "Low", "Medium", "High"
    val consecutiveAbsences: Int,
    val deviceFingerprint: String
)

@Entity(tableName = "attendance_records")
data class AttendanceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: String,
    val studentName: String,
    val batchName: String,
    val subjectName: String,
    val status: String, // "Present", "Absent", "Late", "On Leave"
    val method: String, // "GPS Radius Check", "QR Code Rotation", "Manual Override"
    val latitude: Double,
    val longitude: Double,
    val markedAt: Long, // timestamp
    val isFlagged: Boolean // AI fraud detection flag
)

@Entity(tableName = "leave_requests")
data class LeaveRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val studentId: String,
    val studentName: String,
    val batchName: String,
    val dateFrom: String, // YYYY-MM-DD
    val dateTo: String, // YYYY-MM-DD
    val reason: String,
    val docType: String, // "Medical Certificate.pdf", "Personal Reason"
    val status: String // "Pending", "Approved", "Rejected"
)

@Entity(tableName = "lectures")
data class LectureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectName: String,
    val facultyName: String,
    val batchName: String,
    val timeStart: String, // e.g. "09:00 AM"
    val timeEnd: String, // e.g. "11:00 AM"
    val status: String, // "Upcoming", "Active", "Closed"
    val qrToken: String,
    val dateString: String // YYYY-MM-DD
)

@Entity(tableName = "broadcast_alerts")
data class BroadcastAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val message: String,
    val targetBatch: String, // "All Batches" or specific batch
    val dateSent: String,
    val type: String // "Standard", "Emergency", "Fee Warning", "AI Alert"
)


// --- ROOM DAOS ---

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY name ASC")
    fun getAllStudents(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE id = :id LIMIT 1")
    suspend fun getStudentById(id: String): StudentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudents(students: List<StudentEntity>)

    @Update
    suspend fun updateStudent(student: StudentEntity)
}

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_records ORDER BY markedAt DESC")
    fun getAllAttendanceRecords(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE studentId = :studentId ORDER BY markedAt DESC")
    fun getAttendanceForStudent(studentId: String): Flow<List<AttendanceRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: AttendanceRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<AttendanceRecordEntity>)

    @Query("DELETE FROM attendance_records WHERE id = :id")
    suspend fun deleteRecord(id: Long)
}

@Dao
interface LeaveDao {
    @Query("SELECT * FROM leave_requests ORDER BY id DESC")
    fun getAllLeaveRequests(): Flow<List<LeaveRequestEntity>>

    @Query("SELECT * FROM leave_requests WHERE studentId = :studentId ORDER BY id DESC")
    fun getLeavesForStudent(studentId: String): Flow<List<LeaveRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeave(leave: LeaveRequestEntity)

    @Update
    suspend fun updateLeave(leave: LeaveRequestEntity)
}

@Dao
interface LectureDao {
    @Query("SELECT * FROM lectures ORDER BY id ASC")
    fun getAllLectures(): Flow<List<LectureEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLectures(lectures: List<LectureEntity>)

    @Update
    suspend fun updateLecture(lecture: LectureEntity)
}

@Dao
interface BroadcastDao {
    @Query("SELECT * FROM broadcast_alerts ORDER BY id DESC")
    fun getAllAlerts(): Flow<List<BroadcastAlertEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: BroadcastAlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlerts(alerts: List<BroadcastAlertEntity>)
}


// --- ROOM DATABASE ---

@Database(
    entities = [
        StudentEntity::class,
        AttendanceRecordEntity::class,
        LeaveRequestEntity::class,
        LectureEntity::class,
        BroadcastAlertEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun leaveDao(): LeaveDao
    abstract fun lectureDao(): LectureDao
    abstract fun broadcastDao(): BroadcastDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pinac_attend_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}


// --- UNIFIED REPOSITORY WITH AUTO SEED DATA CONSTRUCTOR ---

class SmartAttendRepository(private val db: AppDatabase) {
    val students: Flow<List<StudentEntity>> = db.studentDao().getAllStudents()
    val allAttendance: Flow<List<AttendanceRecordEntity>> = db.attendanceDao().getAllAttendanceRecords()
    val allLeaves: Flow<List<LeaveRequestEntity>> = db.leaveDao().getAllLeaveRequests()
    val allLectures: Flow<List<LectureEntity>> = db.lectureDao().getAllLectures()
    val allAlerts: Flow<List<BroadcastAlertEntity>> = db.broadcastDao().getAllAlerts()

    suspend fun updateStudent(student: StudentEntity) = db.studentDao().updateStudent(student)
    fun getStudentAttendance(studentId: String): Flow<List<AttendanceRecordEntity>> = db.attendanceDao().getAttendanceForStudent(studentId)
    fun getStudentLeaves(studentId: String): Flow<List<LeaveRequestEntity>> = db.leaveDao().getLeavesForStudent(studentId)

    suspend fun insertAttendanceRecord(record: AttendanceRecordEntity) {
        db.attendanceDao().insertRecord(record)
        
        // Dynamically adjust student attendance after inserting a record
        val user = db.studentDao().getStudentById(record.studentId)
        if (user != null) {
            val history = db.attendanceDao().getAttendanceForStudent(record.studentId)
            // recalculate mock rate
            val currentTotal = 40 // let's assume total 40 lectures held
            val markedPresentCount = if (record.status == "Present" || record.status == "Late") 1 else 0
            val newRateString = ((user.attendanceRate * currentTotal + markedPresentCount * 100) / (currentTotal + 1))
            val updatedRate = java.lang.Float.max(10.0f, java.lang.Float.min(100.0f, newRateString))
            
            // Adjust risk level based on rate
            val riskLevel = when {
                updatedRate < 75.0f -> "High"
                updatedRate < 85.0f -> "Medium"
                else -> "Low"
            }
            db.studentDao().updateStudent(user.copy(
                attendanceRate = updatedRate,
                riskScore = (100.0f - updatedRate) / 100.0f,
                riskLevel = riskLevel
            ))
        }
    }

    suspend fun addLeaveRequest(leave: LeaveRequestEntity) = db.leaveDao().insertLeave(leave)
    suspend fun updateLeaveStatus(leave: LeaveRequestEntity) = db.leaveDao().updateLeave(leave)
    suspend fun updateLecture(lecture: LectureEntity) = db.lectureDao().updateLecture(lecture)
    suspend fun sendBroadcast(alert: BroadcastAlertEntity) = db.broadcastDao().insertAlert(alert)

    // Pre-seed mock data if empty
    suspend fun checkAndSeedDatabase() {
        val studentList = db.studentDao().getAllStudents()
        // Simple first-check by fetching from database
        val existingStudents = db.studentDao().getStudentById("std01")
        if (existingStudents == null) {
            // Seed Students
            val studentsBatch = listOf(
                StudentEntity(
                    id = "std01",
                    name = "Pranav Godbole",
                    email = "pranav.g@pinac.edu",
                    batchName = "VFX 2025-A",
                    attendanceRate = 82.5f,
                    feeStatus = "Paid",
                    feeAmount = 125000.0,
                    registrationNo = "PINAC-25-3042",
                    riskScore = 0.35f,
                    riskLevel = "Medium",
                    consecutiveAbsences = 2,
                    deviceFingerprint = "DEVICE-A98B7C"
                ),
                StudentEntity(
                    id = "std02",
                    name = "Shreya Deshmukh",
                    email = "shreya.d@pinac.edu",
                    batchName = "3D Animation 2025-B",
                    attendanceRate = 72.0f, // Defaulter (< 75%)
                    feeStatus = "Due",
                    feeAmount = 145000.0,
                    registrationNo = "PINAC-25-8021",
                    riskScore = 0.81f,
                    riskLevel = "High",
                    consecutiveAbsences = 4,
                    deviceFingerprint = "DEVICE-F45D8E"
                ),
                StudentEntity(
                    id = "std03",
                    name = "Aditya Kulkarni",
                    email = "aditya.k@pinac.edu",
                    batchName = "VFX 2025-A",
                    attendanceRate = 96.2f,
                    feeStatus = "Paid",
                    feeAmount = 125000.0,
                    registrationNo = "PINAC-25-1089",
                    riskScore = 0.04f,
                    riskLevel = "Low",
                    consecutiveAbsences = 0,
                    deviceFingerprint = "DEVICE-X22G99"
                ),
                StudentEntity(
                    id = "std04",
                    name = "Nikhil Salunkhe",
                    email = "nikhil.s@pinac.edu",
                    batchName = "VFX 2025-A",
                    attendanceRate = 64.5f, // Defaulter
                    feeStatus = "Overdue",
                    feeAmount = 90000.0,
                    registrationNo = "PINAC-25-4122",
                    riskScore = 0.95f,
                    riskLevel = "High",
                    consecutiveAbsences = 5,
                    deviceFingerprint = "DEVICE-F45D8E" // Duplicate with Shreya (Anti-Proxy Flag!)
                ),
                StudentEntity(
                    id = "std05",
                    name = "Mansi Patil",
                    email = "mansi.p@pinac.edu",
                    batchName = "Concept Art 2025",
                    attendanceRate = 89.0f,
                    feeStatus = "Paid",
                    feeAmount = 110000.0,
                    registrationNo = "PINAC-25-5021",
                    riskScore = 0.12f,
                    riskLevel = "Low",
                    consecutiveAbsences = 1,
                    deviceFingerprint = "DEVICE-M50R45"
                )
            )
            db.studentDao().insertStudents(studentsBatch)

            // Seed Lectures
            val lecturesBatch = listOf(
                LectureEntity(
                    subjectName = "Advanced Compositing in Nuke",
                    facultyName = "Prof. Milind Jadhav",
                    batchName = "VFX 2025-A",
                    timeStart = "09:00 AM",
                    timeEnd = "11:30 AM",
                    status = "Active", // Live lecture ready to be marked!
                    qrToken = "NUS-9028-PINAC-COMP-882",
                    dateString = "2026-05-26"
                ),
                LectureEntity(
                    subjectName = "Character Sculpting (ZBrush)",
                    facultyName = "Prof. Sneha Ranade",
                    batchName = "3D Animation 2025-B",
                    timeStart = "12:00 PM",
                    timeEnd = "02:30 PM",
                    status = "Upcoming",
                    qrToken = "ZBR-2201-PINAC-SCULPT-492",
                    dateString = "2026-05-26"
                ),
                LectureEntity(
                    subjectName = "Dynamics & Particle Systems",
                    facultyName = "Prof. Rajesh Joshi",
                    batchName = "VFX 2025-A",
                    timeStart = "03:00 PM",
                    timeEnd = "05:00 PM",
                    status = "Upcoming",
                    qrToken = "DYN-3304-PINAC-PART-121",
                    dateString = "2026-05-26"
                )
            )
            db.lectureDao().insertLectures(lecturesBatch)

            // Seed historical Attendances (so calendar views are populated and risk statistics are real)
            val today = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L
            val historicalRecords = listOf(
                // Pranav
                AttendanceRecordEntity(studentId = "std01", studentName = "Pranav Godbole", batchName = "VFX 2025-A", subjectName = "Digital Painting", status = "Present", method = "GPS Radius Check", latitude = 18.5204, longitude = 73.8567, markedAt = today - 1 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std01", studentName = "Pranav Godbole", batchName = "VFX 2025-A", subjectName = "3D Lighting basics", status = "Absent", method = "Manual Override", latitude = 0.0, longitude = 0.0, markedAt = today - 2 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std01", studentName = "Pranav Godbole", batchName = "VFX 2025-A", subjectName = "Digital Painting", status = "Present", method = "QR Code Rotation", latitude = 18.5205, longitude = 73.8569, markedAt = today - 3 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std01", studentName = "Pranav Godbole", batchName = "VFX 2025-A", subjectName = "Advanced Compositing", status = "On Leave", method = "Manual Override", latitude = 0.0, longitude = 0.0, markedAt = today - 4 * dayMs, isFlagged = false),
                
                // Shreya
                AttendanceRecordEntity(studentId = "std02", studentName = "Shreya Deshmukh", batchName = "3D Animation 2025-B", subjectName = "Maya Rigging", status = "Absent", method = "Manual Override", latitude = 0.0, longitude = 0.0, markedAt = today - 1 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std02", studentName = "Shreya Deshmukh", batchName = "3D Animation 2025-B", subjectName = "Texture Mapping", status = "Absent", method = "Manual Override", latitude = 0.0, longitude = 0.0, markedAt = today - 2 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std02", studentName = "Shreya Deshmukh", batchName = "3D Animation 2025-B", subjectName = "Maya Rigging", status = "Present", method = "GPS Radius Check", latitude = 18.5242, longitude = 73.8610, markedAt = today - 3 * dayMs, isFlagged = true), // Flagged due to multi-device binding mismatch
                AttendanceRecordEntity(studentId = "std02", studentName = "Shreya Deshmukh", batchName = "3D Animation 2025-B", subjectName = "Digital Modeling", status = "Absent", method = "Manual Override", latitude = 0.0, longitude = 0.0, markedAt = today - 4 * dayMs, isFlagged = false),

                // Aditya
                AttendanceRecordEntity(studentId = "std03", studentName = "Aditya Kulkarni", batchName = "VFX 2025-A", subjectName = "Digital Painting", status = "Present", method = "GPS Radius Check", latitude = 18.5204, longitude = 73.8567, markedAt = today - 1 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std03", studentName = "Aditya Kulkarni", batchName = "VFX 2025-A", subjectName = "3D Lighting basics", status = "Present", method = "QR Code Rotation", latitude = 18.5203, longitude = 73.8566, markedAt = today - 2 * dayMs, isFlagged = false),
                AttendanceRecordEntity(studentId = "std03", studentName = "Aditya Kulkarni", batchName = "VFX 2025-A", subjectName = "Digital Painting", status = "Present", method = "GPS Radius Check", latitude = 18.5204, longitude = 73.8567, markedAt = today - 3 * dayMs, isFlagged = false)
            )
            db.attendanceDao().insertRecords(historicalRecords)

            // Seed Leave Requests
            db.leaveDao().insertLeave(
                LeaveRequestEntity(
                    studentId = "std01",
                    studentName = "Pranav Godbole",
                    batchName = "VFX 2025-A",
                    dateFrom = "2026-05-22",
                    dateTo = "2026-05-23",
                    reason = "Unwell, diagnosed with viral fever. Under rest recommendation.",
                    docType = "Medical Certificate.pdf",
                    status = "Approved"
                )
            )
            db.leaveDao().insertLeave(
                LeaveRequestEntity(
                    studentId = "std02",
                    studentName = "Shreya Deshmukh",
                    batchName = "3D Animation 2025-B",
                    dateFrom = "2026-05-27",
                    dateTo = "2026-05-28",
                    reason = "Family emergency back in Nagpur. Booking travel ticket.",
                    docType = "Train Ticket Proof.jpg",
                    status = "Pending"
                )
            )

            // Seed Alerts
            val alertsBatch = listOf(
                BroadcastAlertEntity(
                    title = "System Update: Multi-Device Binding Active",
                    message = "In our effort to stop proxy attendances, student accounts are bound firmly to registered hardware IDs. Flagging active anomalies instantly.",
                    targetBatch = "All Batches",
                    dateSent = "22 May 2026",
                    type = "Emergency"
                ),
                BroadcastAlertEntity(
                    title = "VFX Portfolio Submission Deadline",
                    message = "Reminder: Final compositing reels must be stored in the academy directory by Friday evening. No exception policy.",
                    targetBatch = "VFX 2025-A",
                    dateSent = "24 May 2026",
                    type = "Standard"
                ),
                BroadcastAlertEntity(
                    title = "Fee Reminder for Overdue Accounts",
                    message = "Second Installment schedules for batch 2025 are overdue. Verify invoices or contact financial administrator immediately.",
                    targetBatch = "All Batches",
                    dateSent = "25 May 2026",
                    type = "Fee Warning"
                )
            )
            db.broadcastDao().insertAlerts(alertsBatch)
        }
    }
}
