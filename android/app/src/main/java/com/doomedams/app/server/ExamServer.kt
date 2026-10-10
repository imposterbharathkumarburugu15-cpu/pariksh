package com.doomedams.app.server

import com.doomedams.app.database.AppDatabase
import com.doomedams.app.database.audit.AuditEngine
import com.doomedams.app.database.repository.AttemptRepository
import com.doomedams.app.database.repository.ExamRepository
import com.doomedams.app.server.auth.SessionManager
import com.doomedams.app.server.routes.configureExamServerRoutes
import com.doomedams.app.server.security.RateLimiter
import com.doomedams.app.server.security.TlsCertificateManager
import com.doomedams.app.server.security.TlsConfiguration
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.cors.CORS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Server Lifecycle Status
 */
enum class ServerLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Server Status Snapshot
 */
data class ServerStatus(
    val state: ServerLifecycleState,
    val host: String,
    val port: Int,
    val tlsEnabled: Boolean,
    val sha256Fingerprint: String?,
    val activeSessions: Int,
    val lastError: String? = null
)

/**
 * Server Configuration
 */
data class ServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8443,
    val enableTls: Boolean = true,
    val tlsConfig: TlsConfiguration? = null,
    val tlsStorageDir: java.io.File? = null
) {
    init {
        require(port in 1024..65535) { "Port must be between 1024 and 65535 (inclusive)" }
    }
}

/**
 * Embedded Ktor Server for offline examination coordination on the examiner's Android device.
 */
class ExamServer(
    private val database: AppDatabase,
    private val config: ServerConfig = ServerConfig(),
    val sessionManager: SessionManager = SessionManager()
) {
    private val auditEngine = AuditEngine(database)
    private val attemptRepository = AttemptRepository(database, auditEngine)
    private val examRepository = ExamRepository(database, auditEngine)
    private val rateLimiter = RateLimiter()
    private val lifecycleMutex = Mutex()

    private var engine: ApplicationEngine? = null

    @Volatile
    private var currentState: ServerLifecycleState = ServerLifecycleState.STOPPED

    @Volatile
    private var lastErrorMessage: String? = null

    @Volatile
    private var activeTlsConfig: TlsConfiguration? = config.tlsConfig

    @Synchronized
    fun isRunning(): Boolean = currentState == ServerLifecycleState.RUNNING

    @Synchronized
    fun getStatus(): ServerStatus {
        return ServerStatus(
            state = currentState,
            host = config.host,
            port = config.port,
            tlsEnabled = config.enableTls,
            sha256Fingerprint = activeTlsConfig?.sha256Fingerprint,
            activeSessions = sessionManager.getActiveSessionCount(),
            lastError = lastErrorMessage
        )
    }

    suspend fun start(): ServerStatus = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (currentState == ServerLifecycleState.RUNNING) {
                return@withContext getStatus()
            }

            currentState = ServerLifecycleState.STARTING
            lastErrorMessage = null

            try {
                val targetHost = config.host
                val targetPort = config.port
                val isTlsEnabled = config.enableTls

                // Configure TLS if requested
                if (isTlsEnabled && activeTlsConfig == null) {
                    activeTlsConfig = if (config.tlsStorageDir != null) {
                        TlsCertificateManager.getOrCreatePersistentCertificate(config.tlsStorageDir)
                    } else {
                        TlsCertificateManager.generateSelfSignedCertificate()
                    }
                }

                val currentTls = activeTlsConfig
                val serverEngine = if (isTlsEnabled && currentTls != null) {
                    embeddedServer(
                        factory = CIO,
                        environment = applicationEngineEnvironment {
                            sslConnector(
                                keyStore = currentTls.keyStore,
                                keyAlias = "doomedams_examiner",
                                keyStorePassword = { currentTls.keyStorePassword },
                                privateKeyPassword = { currentTls.keyStorePassword }
                            ) {
                                this.host = targetHost
                                this.port = targetPort
                            }
                            module {
                                install(CORS) {
                                    anyHost()
                                    allowHeader(HttpHeaders.ContentType)
                                    allowHeader(HttpHeaders.Authorization)
                                    allowMethod(HttpMethod.Options)
                                    allowMethod(HttpMethod.Put)
                                    allowMethod(HttpMethod.Post)
                                    allowMethod(HttpMethod.Get)
                                }
                                configureExamServerRoutes(
                                    database = database,
                                    sessionManager = sessionManager,
                                    auditEngine = auditEngine,
                                    attemptRepository = attemptRepository,
                                    examRepository = examRepository,
                                    rateLimiter = rateLimiter,
                                    tlsFingerprint = currentTls.sha256Fingerprint
                                )
                            }
                        }
                    )
                } else {
                    // If TLS was explicitly disabled for local testing
                    embeddedServer(
                        factory = CIO,
                        port = config.port,
                        host = config.host
                    ) {
                        install(CORS) {
                            anyHost()
                            allowHeader(HttpHeaders.ContentType)
                            allowHeader(HttpHeaders.Authorization)
                            allowMethod(HttpMethod.Options)
                            allowMethod(HttpMethod.Put)
                            allowMethod(HttpMethod.Post)
                            allowMethod(HttpMethod.Get)
                        }
                        configureExamServerRoutes(
                            database = database,
                            sessionManager = sessionManager,
                            auditEngine = auditEngine,
                            attemptRepository = attemptRepository,
                            examRepository = examRepository,
                            rateLimiter = rateLimiter,
                            tlsFingerprint = null
                        )
                    }
                }

                serverEngine.start(wait = false)
                engine = serverEngine
                currentState = ServerLifecycleState.RUNNING

                auditEngine.recordAuditEvent(
                    auditEventId = "aud_srv_start_${System.currentTimeMillis()}",
                    actorId = "system",
                    actorRole = "SYSTEM_ADMIN",
                    actionType = "SERVER_STARTED",
                    aggregateType = "SERVER",
                    aggregateId = "${config.host}:${config.port}",
                    previousState = null,
                    newState = "{\"port\":${config.port},\"tls\":${config.enableTls}}",
                    operationResult = "SUCCESS"
                )

                getStatus()
            } catch (e: Exception) {
                currentState = ServerLifecycleState.ERROR
                lastErrorMessage = e.message ?: "Failed to start server"
                engine?.stop(1000, 2000)
                engine = null
                throw e
            }
        }
    }

    suspend fun stop(): ServerStatus = lifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (currentState == ServerLifecycleState.STOPPED) {
                return@withContext getStatus()
            }

            currentState = ServerLifecycleState.STOPPING
            try {
                engine?.stop(500, 1000)
                engine = null
                currentState = ServerLifecycleState.STOPPED

                auditEngine.recordAuditEvent(
                    auditEventId = "aud_srv_stop_${System.currentTimeMillis()}",
                    actorId = "system",
                    actorRole = "SYSTEM_ADMIN",
                    actionType = "SERVER_STOPPED",
                    aggregateType = "SERVER",
                    aggregateId = "${config.host}:${config.port}",
                    operationResult = "SUCCESS"
                )
            } catch (e: Exception) {
                currentState = ServerLifecycleState.ERROR
                lastErrorMessage = e.message
            }
            getStatus()
        }
    }
}
