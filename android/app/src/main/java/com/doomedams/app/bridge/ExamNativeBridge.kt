package com.doomedams.app.bridge

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.entity.CandidateResponseEntity
import com.doomedams.app.database.entity.IntegrityEventEntity
import com.doomedams.app.database.entity.SyncOutboxEntity
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

@CapacitorPlugin(name = "ExamNativeBridge")
class ExamNativeBridge : Plugin() {

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun getDb(): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    /**
     * Reliable Monotonic Time:
     * Returns Android's SystemClock.elapsedRealtime() to prevent local wall-clock tampering.
     */
    @PluginMethod
    fun getMonotonicTime(call: PluginCall) {
        val elapsed = SystemClock.elapsedRealtime()
        val currentWallMs = System.currentTimeMillis()
        val res = JSObject().apply {
            put("elapsedRealtimeMs", elapsed)
            put("currentWallMs", currentWallMs)
        }
        call.resolve(res)
    }

    /**
     * Durable Local Answer Storage:
     * Persists candidate response directly into Room SQLite before acknowledging.
     */
    @PluginMethod
    fun saveResponseLocal(call: PluginCall) {
        val attemptId = call.getString("attemptId")
        val questionId = call.getString("questionId")
        val responsePayload = call.getString("responsePayload")
        val revisionSequence = call.getInt("revisionSequence", 1) ?: 1

        if (attemptId == null || questionId == null || responsePayload == null) {
            call.reject("Missing required parameters: attemptId, questionId, responsePayload")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                val existing = db.candidateResponseDao().getResponse(attemptId, questionId)
                val responseId = existing?.responseId ?: "resp_${System.currentTimeMillis()}_${questionId.take(8)}"
                val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

                val entity = CandidateResponseEntity(
                    responseId = responseId,
                    attemptId = attemptId,
                    questionId = questionId,
                    responsePayload = responsePayload,
                    revisionSequence = revisionSequence,
                    savedAt = nowIso
                )
                db.candidateResponseDao().upsertResponse(entity)

                val res = JSObject().apply {
                    put("responseId", responseId)
                    put("revisionSequence", revisionSequence)
                    put("savedAt", nowIso)
                    put("isDurablyPersisted", true)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to save response locally in Room: ${e.message}")
            }
        }
    }

    /**
     * Loads saved attempt snapshot from Room DB on device.
     */
    @PluginMethod
    fun loadAttemptSnapshotLocal(call: PluginCall) {
        val attemptId = call.getString("attemptId")
        if (attemptId == null) {
            call.reject("Missing attemptId")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                val responses = db.candidateResponseDao().getResponsesByAttempt(attemptId)
                val arr = JSArray()
                for (r in responses) {
                    val obj = JSObject().apply {
                        put("responseId", r.responseId)
                        put("questionId", r.questionId)
                        put("responsePayload", r.responsePayload)
                        put("revisionSequence", r.revisionSequence)
                        put("savedAt", r.savedAt)
                    }
                    arr.put(obj)
                }
                val res = JSObject().apply {
                    put("attemptId", attemptId)
                    put("responses", arr)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to load snapshot from Room: ${e.message}")
            }
        }
    }

    /**
     * Enqueues an offline event into sync_outbox.
     */
    @PluginMethod
    fun enqueueSyncEventLocal(call: PluginCall) {
        val eventId = call.getString("eventId")
        val aggregateId = call.getString("aggregateId")
        val eventType = call.getString("eventType")
        val payloadJson = call.getString("payloadJson")

        if (eventId == null || aggregateId == null || eventType == null || payloadJson == null) {
            call.reject("Missing required parameters for sync_outbox")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                val existing = db.syncOutboxDao().getItemByEventId(eventId)
                if (existing != null) {
                    val res = JSObject().apply {
                        put("eventId", eventId)
                        put("status", "ALREADY_ENQUEUED")
                    }
                    call.resolve(res)
                    return@launch
                }

                val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                val outboxEntity = SyncOutboxEntity(
                    outboxId = "out_${System.currentTimeMillis()}_${eventId.take(8)}",
                    eventId = eventId,
                    aggregateId = aggregateId,
                    eventType = eventType,
                    payloadJson = payloadJson,
                    createdAt = nowIso,
                    deliveryStatus = "PENDING",
                    retryCount = 0,
                    lastError = null
                )
                db.syncOutboxDao().enqueue(outboxEntity)

                val res = JSObject().apply {
                    put("eventId", eventId)
                    put("status", "ENQUEUED")
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to enqueue sync event: ${e.message}")
            }
        }
    }

    /**
     * Retrieves all pending and retry-pending events in sync_outbox.
     */
    @PluginMethod
    fun getPendingSyncEventsLocal(call: PluginCall) {
        scope.launch {
            try {
                val db = getDb()
                val pending = db.syncOutboxDao().getDeliverableItems()
                val arr = JSArray()
                for (p in pending) {
                    val obj = JSObject().apply {
                        put("eventId", p.eventId)
                        put("aggregateId", p.aggregateId)
                        put("eventType", p.eventType)
                        put("payloadJson", p.payloadJson)
                        put("createdAt", p.createdAt)
                        put("deliveryStatus", p.deliveryStatus)
                        put("retryCount", p.retryCount)
                        put("lastError", p.lastError)
                    }
                    arr.put(obj)
                }
                val res = JSObject().apply {
                    put("pendingEvents", arr)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to get pending sync events: ${e.message}")
            }
        }
    }

    /**
     * Marks an event in sync_outbox as acknowledged by server.
     */
    @PluginMethod
    fun markSyncEventAcknowledgedLocal(call: PluginCall) {
        val eventId = call.getString("eventId")
        if (eventId == null) {
            call.reject("Missing eventId")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                db.syncOutboxDao().updateStatusByEventId(eventId, "ACKNOWLEDGED")
                val res = JSObject().apply {
                    put("eventId", eventId)
                    put("status", "ACKNOWLEDGED")
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to acknowledge sync event: ${e.message}")
            }
        }
    }

    /**
     * Updates an event's delivery status, retry count, and optional error message.
     * Supports: PENDING, IN_FLIGHT, ACKNOWLEDGED, RETRY_PENDING, CONFLICT, REJECTED, FAILED_PERMANENTLY.
     */
    @PluginMethod
    fun updateSyncEventStatusLocal(call: PluginCall) {
        val eventId = call.getString("eventId")
        val status = call.getString("status")
        val lastError = call.getString("lastError")
        val retryCount = call.getInt("retryCount") ?: 0

        if (eventId == null || status == null) {
            call.reject("Missing eventId or status")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                db.syncOutboxDao().updateDeliveryAttempt(
                    eventId = eventId,
                    status = status,
                    error = lastError,
                    retryCount = retryCount
                )
                val res = JSObject().apply {
                    put("eventId", eventId)
                    put("status", status)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to update sync event status: ${e.message}")
            }
        }
    }

    // ==========================================
    // STEP 8: LOCKDOWN & INTEGRITY MONITOR BRIDGE
    // ==========================================

    /**
     * Inspects device lockdown capabilities and operating mode.
     */
    @PluginMethod
    fun getLockdownCapabilities(call: PluginCall) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) ?: false
        val isProfileOwner = dpm?.isProfileOwnerApp(context.packageName) ?: false
        val isLockTaskPermitted = dpm?.isLockTaskPermitted(context.packageName) ?: false

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val hasUsageAccess = appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED

        val pm = context.packageManager
        val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        val deviceMode = if (isDeviceOwner || isLockTaskPermitted) "MANAGED_DEVICE" else "PERSONAL_PHONE"
        val lockdownSupport = if (isLockTaskPermitted) "FULL" else "SCREEN_PINNING_ONLY_OR_UNSUPPORTED"

        val res = JSObject().apply {
            put("deviceMode", deviceMode)
            put("isDeviceOwner", isDeviceOwner)
            put("isProfileOwner", isProfileOwner)
            put("isLockTaskPermitted", isLockTaskPermitted)
            put("hasUsageAccess", hasUsageAccess)
            put("hasCamera", hasCamera)
            put("cameraMonitoringStatus", "NOT_AVAILABLE") // Honestly reported: no bundled offline ML model
            put("lockdownSupport", lockdownSupport)
        }
        call.resolve(res)
    }

    /**
     * Checks current Lock Task Mode state.
     */
    @PluginMethod
    fun getCurrentLockdownStatus(call: PluginCall) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val lockTaskState = am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE

        val stateStr = when (lockTaskState) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> "LOCKED"
            ActivityManager.LOCK_TASK_MODE_PINNED -> "PINNED"
            else -> "NONE"
        }

        val res = JSObject().apply {
            put("lockTaskModeState", stateStr)
            put("isLockTaskActive", lockTaskState != ActivityManager.LOCK_TASK_MODE_NONE)
            put("isManagedLockdown", lockTaskState == ActivityManager.LOCK_TASK_MODE_LOCKED)
            put("isPinnedOnly", lockTaskState == ActivityManager.LOCK_TASK_MODE_PINNED)
        }
        call.resolve(res)
    }

    /**
     * Checks DevicePolicyManager details.
     */
    @PluginMethod
    fun checkDeviceManagementStatus(call: PluginCall) {
        getLockdownCapabilities(call)
    }

    /**
     * Starts Exam Lockdown on main thread.
     */
    @PluginMethod
    fun startExamLockdown(call: PluginCall) {
        activity?.runOnUiThread {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val isLockTaskPermitted = dpm?.isLockTaskPermitted(context.packageName) ?: false

            try {
                activity?.startLockTask()
                val postState = am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
                val stateStr = when (postState) {
                    ActivityManager.LOCK_TASK_MODE_LOCKED -> "LOCKED"
                    ActivityManager.LOCK_TASK_MODE_PINNED -> "PINNED"
                    else -> "NONE"
                }

                val res = JSObject().apply {
                    put("success", true)
                    put("lockTaskModeState", stateStr)
                    put("isManagedLockdown", postState == ActivityManager.LOCK_TASK_MODE_LOCKED)
                    put("isPinnedOnly", postState == ActivityManager.LOCK_TASK_MODE_PINNED)
                    put("isPermittedByDpm", isLockTaskPermitted)
                }
                call.resolve(res)
            } catch (e: Exception) {
                val res = JSObject().apply {
                    put("success", false)
                    put("lockTaskModeState", "NONE")
                    put("isManagedLockdown", false)
                    put("error", "LOCK_TASK_START_FAILED")
                    put("message", e.message ?: "Failed to start lock task")
                }
                call.resolve(res)
            }
        } ?: run {
            call.reject("Activity unavailable to start lock task")
        }
    }

    /**
     * Stops Exam Lockdown on main thread.
     */
    @PluginMethod
    fun stopExamLockdown(call: PluginCall) {
        activity?.runOnUiThread {
            try {
                activity?.stopLockTask()
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val postState = am?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE
                val stateStr = when (postState) {
                    ActivityManager.LOCK_TASK_MODE_LOCKED -> "LOCKED"
                    ActivityManager.LOCK_TASK_MODE_PINNED -> "PINNED"
                    else -> "NONE"
                }

                val res = JSObject().apply {
                    put("success", true)
                    put("lockTaskModeState", stateStr)
                }
                call.resolve(res)
            } catch (e: Exception) {
                val res = JSObject().apply {
                    put("success", false)
                    put("error", "LOCK_TASK_STOP_FAILED")
                    put("message", e.message ?: "Failed to stop lock task")
                }
                call.resolve(res)
            }
        } ?: run {
            call.reject("Activity unavailable to stop lock task")
        }
    }

    /**
     * Records a lifecycle interruption (app background, window blur, lock task exit).
     */
    @PluginMethod
    fun recordLifecycleInterruption(call: PluginCall) {
        val attemptId = call.getString("attemptId")
        val examId = call.getString("examId")
        val studentId = call.getString("studentId")
        val eventCategory = call.getString("eventCategory") ?: "EXAM_APP_BACKGROUND"
        val sequenceNumber = call.getInt("sequenceNumber", 1)?.toLong() ?: 1L
        val sourceDeviceId = call.getString("sourceDeviceId") ?: "device_local"

        if (attemptId == null || examId == null || studentId == null) {
            call.reject("Missing required parameters: attemptId, examId, studentId")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                val elapsedRealtimeMs = SystemClock.elapsedRealtime()
                val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                val eventId = "integ_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

                // Detect external app if UsageStats permission is granted
                var detectedPackage: String? = null
                var detectedAppName: String? = null
                var detectionMethod = "APP_LIFECYCLE"
                var confidence = "LOW"

                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                val hasUsage = appOps?.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                ) == AppOpsManager.MODE_ALLOWED

                if (hasUsage) {
                    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    val endTime = System.currentTimeMillis()
                    val startTime = endTime - 15000 // 15s window
                    val usageEvents = usm?.queryEvents(startTime, endTime)
                    val event = UsageEvents.Event()
                    var latestExternalPkg: String? = null

                    while (usageEvents != null && usageEvents.hasNextEvent()) {
                        usageEvents.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName != context.packageName) {
                            latestExternalPkg = event.packageName
                        }
                    }

                    if (latestExternalPkg != null) {
                        detectedPackage = latestExternalPkg
                        detectionMethod = "USAGE_STATS"
                        confidence = "HIGH"
                        try {
                            val pm = context.packageManager
                            val appInfo = pm.getApplicationInfo(latestExternalPkg, 0)
                            detectedAppName = pm.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            detectedAppName = latestExternalPkg
                        }
                    } else {
                        detectedAppName = "APP_NAME_UNAVAILABLE"
                    }
                } else {
                    detectedAppName = "APP_NAME_UNAVAILABLE"
                }

                val payloadObj = JSONObject().apply {
                    put("monotonicElapsedMs", elapsedRealtimeMs)
                    put("detectionMethod", detectionMethod)
                    put("confidenceLevel", confidence)
                }

                val entity = IntegrityEventEntity(
                    eventId = eventId,
                    examId = examId,
                    attemptId = attemptId,
                    studentId = studentId,
                    eventCategory = eventCategory,
                    deviceReportedAt = nowIso,
                    receivedAt = null,
                    sequenceNumber = sequenceNumber,
                    sourceDeviceId = sourceDeviceId,
                    payloadJson = payloadObj.toString(),
                    payloadHash = null,
                    appPackageName = detectedPackage,
                    displayedAppName = detectedAppName,
                    ingestionStatus = "PENDING"
                )
                db.integrityEventDao().insertEvent(entity)

                // Enqueue into sync_outbox for offline queuing
                val outboxPayload = JSONObject().apply {
                    put("eventId", eventId)
                    put("examId", examId)
                    put("attemptId", attemptId)
                    put("studentId", studentId)
                    put("eventCategory", eventCategory)
                    put("deviceReportedAt", nowIso)
                    put("sequenceNumber", sequenceNumber)
                    put("sourceDeviceId", sourceDeviceId)
                    put("appPackageName", detectedPackage ?: JSONObject.NULL)
                    put("displayedAppName", detectedAppName ?: JSONObject.NULL)
                    put("detectionMethod", detectionMethod)
                    put("confidenceLevel", confidence)
                    put("payloadJson", payloadObj.toString())
                }

                val outboxEntity = SyncOutboxEntity(
                    outboxId = "out_integ_${System.currentTimeMillis()}_${eventId.take(8)}",
                    eventId = eventId,
                    aggregateId = attemptId,
                    eventType = "INTEGRITY_EVENT",
                    payloadJson = outboxPayload.toString(),
                    createdAt = nowIso,
                    deliveryStatus = "PENDING",
                    retryCount = 0,
                    lastError = null
                )
                db.syncOutboxDao().enqueue(outboxEntity)

                val res = JSObject().apply {
                    put("eventId", eventId)
                    put("eventCategory", eventCategory)
                    put("deviceReportedAt", nowIso)
                    put("appPackageName", detectedPackage)
                    put("displayedAppName", detectedAppName)
                    put("detectionMethod", detectionMethod)
                    put("confidenceLevel", confidence)
                    put("isPersistedLocally", true)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to record lifecycle interruption in Room: ${e.message}")
            }
        }
    }

    /**
     * Records exam resume or return to foreground.
     */
    @PluginMethod
    fun recordExamResume(call: PluginCall) {
        val attemptId = call.getString("attemptId")
        val examId = call.getString("examId")
        val studentId = call.getString("studentId")
        val sequenceNumber = call.getInt("sequenceNumber", 1)?.toLong() ?: 1L
        val sourceDeviceId = call.getString("sourceDeviceId") ?: "device_local"

        if (attemptId == null || examId == null || studentId == null) {
            call.reject("Missing required parameters: attemptId, examId, studentId")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                val elapsedRealtimeMs = SystemClock.elapsedRealtime()
                val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                val eventId = "integ_res_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

                val payloadObj = JSONObject().apply {
                    put("monotonicElapsedMs", elapsedRealtimeMs)
                    put("detectionMethod", "APP_LIFECYCLE")
                    put("confidenceLevel", "HIGH")
                }

                val entity = IntegrityEventEntity(
                    eventId = eventId,
                    examId = examId,
                    attemptId = attemptId,
                    studentId = studentId,
                    eventCategory = "EXAM_APP_RESUMED",
                    deviceReportedAt = nowIso,
                    receivedAt = null,
                    sequenceNumber = sequenceNumber,
                    sourceDeviceId = sourceDeviceId,
                    payloadJson = payloadObj.toString(),
                    payloadHash = null,
                    appPackageName = null,
                    displayedAppName = null,
                    ingestionStatus = "PENDING"
                )
                db.integrityEventDao().insertEvent(entity)

                val outboxPayload = JSONObject().apply {
                    put("eventId", eventId)
                    put("examId", examId)
                    put("attemptId", attemptId)
                    put("studentId", studentId)
                    put("eventCategory", "EXAM_APP_RESUMED")
                    put("deviceReportedAt", nowIso)
                    put("sequenceNumber", sequenceNumber)
                    put("sourceDeviceId", sourceDeviceId)
                    put("detectionMethod", "APP_LIFECYCLE")
                    put("confidenceLevel", "HIGH")
                    put("payloadJson", payloadObj.toString())
                }

                val outboxEntity = SyncOutboxEntity(
                    outboxId = "out_integ_${System.currentTimeMillis()}_${eventId.take(8)}",
                    eventId = eventId,
                    aggregateId = attemptId,
                    eventType = "INTEGRITY_EVENT",
                    payloadJson = outboxPayload.toString(),
                    createdAt = nowIso,
                    deliveryStatus = "PENDING",
                    retryCount = 0,
                    lastError = null
                )
                db.syncOutboxDao().enqueue(outboxEntity)

                val res = JSObject().apply {
                    put("eventId", eventId)
                    put("eventCategory", "EXAM_APP_RESUMED")
                    put("deviceReportedAt", nowIso)
                    put("isPersistedLocally", true)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to record exam resume in Room: ${e.message}")
            }
        }
    }

    /**
     * Checks integrity monitor status.
     */
    @PluginMethod
    fun getIntegrityMonitorStatus(call: PluginCall) {
        val attemptId = call.getString("attemptId")
        scope.launch {
            try {
                val db = getDb()
                val events = if (attemptId != null) {
                    db.integrityEventDao().getEventsByAttempt(attemptId)
                } else {
                    db.integrityEventDao().getAllEvents()
                }
                val pendingOutbox = db.syncOutboxDao().getPendingItems().filter { it.eventType == "INTEGRITY_EVENT" }

                val res = JSObject().apply {
                    put("recordedEventsCount", events.size)
                    put("pendingSyncCount", pendingOutbox.size)
                    put("monitoringActive", true)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to get integrity monitor status: ${e.message}")
            }
        }
    }

    /**
     * Checks whether PACKAGE_USAGE_STATS permission is granted.
     */
    @PluginMethod
    fun getUsageAccessStatus(call: PluginCall) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        val hasUsage = appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED

        val res = JSObject().apply {
            put("hasUsageAccess", hasUsage)
        }
        call.resolve(res)
    }

    /**
     * Retrieves recorded integrity events from Room DB for active attempt.
     */
    @PluginMethod
    fun getRecordedIntegrityEvents(call: PluginCall) {
        val attemptId = call.getString("attemptId")
        if (attemptId == null) {
            call.reject("Missing attemptId")
            return
        }

        scope.launch {
            try {
                val db = getDb()
                val events = db.integrityEventDao().getEventsByAttempt(attemptId)
                val arr = JSArray()
                for (ev in events) {
                    val obj = JSObject().apply {
                        put("eventId", ev.eventId)
                        put("examId", ev.examId)
                        put("attemptId", ev.attemptId)
                        put("studentId", ev.studentId)
                        put("eventCategory", ev.eventCategory)
                        put("deviceReportedAt", ev.deviceReportedAt)
                        put("receivedAt", ev.receivedAt)
                        put("sequenceNumber", ev.sequenceNumber)
                        put("sourceDeviceId", ev.sourceDeviceId)
                        put("appPackageName", ev.appPackageName)
                        put("displayedAppName", ev.displayedAppName)
                        put("payloadJson", ev.payloadJson)
                        put("ingestionStatus", ev.ingestionStatus)
                    }
                    arr.put(obj)
                }
                val res = JSObject().apply {
                    put("attemptId", attemptId)
                    put("events", arr)
                }
                call.resolve(res)
            } catch (e: Exception) {
                call.reject("Failed to get recorded integrity events: ${e.message}")
            }
        }
    }
}
