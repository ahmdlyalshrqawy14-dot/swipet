package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TransferManager {
    private const val TAG = "TransferManager"
    private const val SERVICE_TYPE = "_swipe_p2p._tcp"
    private const val BUFFER_SIZE = 128 * 1024 // 128KB for high-speed local transfer
    private const val FIXED_PORT = 8283

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()
    private val stateMutex = Mutex()
    private val lastFileProgressUpdateMap = ConcurrentHashMap<Int, Long>()

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Session and Retry Tracking
    private var currentSessionId: String? = null
    private var lastSenderUris = listOf<Uri>()
    private var lastReceiverCode: String? = null
    private var lastReceiverIp: String? = null
    private var lastReceiverPort: Int? = null
    private var discoveryRetryCount = 0
    private var registrationRetryCount = 0
    private var currentSessionCode: String = ""
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    // Network resources
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var appContext: Context? = null
    private var lastWakeLockRefreshTime = 0L
    private const val MAX_WAKELOCK_DURATION_MS = 30 * 60 * 1000L // 30 minutes max
    private var wakeLockStartTime = 0L

    fun pokeWakeLock() {
        val now = System.currentTimeMillis()
        
        // Check if wakelock has exceeded maximum duration
        if (wakeLockStartTime > 0 && (now - wakeLockStartTime) > MAX_WAKELOCK_DURATION_MS) {
            Log.w(TAG, "WakeLock exceeded max duration, releasing")
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            return
        }
        
        if (now - lastWakeLockRefreshTime > 15000L) { // refresh at most once every 15 seconds
            lastWakeLockRefreshTime = now
            try {
                wakeLock?.let {
                    if (!it.isHeld) {
                        it.acquire(60000) // Re-acquire with 60 second timeout
                        Log.d(TAG, "Wake lock was not held, acquired successfully")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing wake lock", e)
            }
        }
    }

    // Sockets and Coroutine Jobs tracking
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var statsJob: Job? = null

    // Speed Tracking
    private val totalBytesTransferred = AtomicLong(0L)
    private var lastBytesTransferred = 0L
    private var lastTimeMs = 0L
    private var sessionStartTimeMs = 0L

    // Local Files Mapping (for Sender)
    private val senderFilesMap = ConcurrentHashMap<Int, Uri>()

    // Local Wifi and Power Locks
    private fun acquireMulticastLock(context: Context) {
        try {
            appContext = context.applicationContext
            lastWakeLockRefreshTime = System.currentTimeMillis()
            wakeLockStartTime = System.currentTimeMillis()

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("SwipeMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired successfully")

            wifiLock = wifiManager.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    WifiManager.WIFI_MODE_FULL
                },
                "SwipeWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Wifi lock acquired successfully")

            val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Swipe:TransferWakeLock").apply {
                setReferenceCounted(false)
                acquire() // Acquire with timeout protection
            }
            Log.d(TAG, "Wake lock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null
            Log.d(TAG, "Multicast lock released")

            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock = null
            Log.d(TAG, "Wifi lock released")

            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.d(TAG, "Wake lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release locks", e)
        }
    }

    // --- IP AND CODE CALCULATION HELPERS ---
    data class IpInfo(val ip: String, val prefixLength: Int)

    private fun getLocalIpInfo(): IpInfo? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback: IpInfo? = null
            for (intf in Collections.list(interfaces)) {
                val name = intf.name.lowercase()
                val isWlan = name.contains("wlan")
                val isIgnored = name.contains("tun") || name.contains("ppp") || name.contains("rmnet") || name.contains("pdp") || name.contains("ccmni")
                
                for (interfaceAddr in intf.interfaceAddresses) {
                    val addr = interfaceAddr.address
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        val prefix = interfaceAddr.networkPrefixLength.toInt()
                        
                        if (isWlan) {
                            return IpInfo(ip, prefix)
                        } else if (!isIgnored && fallback == null) {
                            fallback = IpInfo(ip, prefix)
                        }
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP info", e)
        }
        return null
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() == -1) {
                    throw EOFException("Reached EOF while skipping input stream")
                }
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    // --- SENDER (SERVER) FLOW ---
    fun startSender(context: Context, uris: List<Uri>, customPaths: List<String?>? = null) {
        lastSenderUris = uris
        cleanup()
        registrationRetryCount = 0
        currentSessionId = java.util.UUID.randomUUID().toString()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        acquireMulticastLock(context)

        // Resolve files
        val resolvedFiles = mutableListOf<TransferFile>()
        senderFilesMap.clear()
        uris.forEachIndexed { index, uri ->
            val fileId = index + 1
            if (uri.scheme == "text") {
                val textData = customPaths?.getOrNull(index) ?: ""
                resolvedFiles.add(
                    TransferFile(
                        id = fileId,
                        name = "swipe_text_share:$textData",
                        size = textData.toByteArray(Charsets.UTF_8).size.toLong(),
                        uriString = uri.toString(),
                        status = TransferStatus.PENDING
                    )
                )
                senderFilesMap[fileId] = uri
            } else {
                val (defaultName, size) = getFileNameAndSize(context, uri)
                val name = customPaths?.getOrNull(index) ?: defaultName
                resolvedFiles.add(
                    TransferFile(
                        id = fileId,
                        name = name,
                        size = size,
                        uriString = uri.toString(),
                        status = TransferStatus.PENDING
                    )
                )
                senderFilesMap[fileId] = uri
            }
        }

        if (resolvedFiles.isEmpty()) {
            _state.value = TransferState.Finished(
                success = false,
                filesCount = 0,
                totalSize = 0L,
                timeElapsedSec = 0,
                averageSpeedBytesPerSec = 0,
                isSender = true,
                errorMsg = context.getString(R.string.error_no_valid_files)
            )
            cleanup()
            return
        }

        val ipInfo = getLocalIpInfo()
        val localIp = ipInfo?.ip ?: "127.0.0.1"

        // Generate a random 4-digit code, completely decoupled from IP
        val code = (1000..9999).random().toString()
        currentSessionCode = code

        var sSocket: ServerSocket? = null
        var boundPort = 0
        try {
            // Bind to port 0 to let the OS automatically assign any free ephemeral port
            sSocket = ServerSocket(0)
            boundPort = sSocket.localPort
            Log.d(TAG, "Successfully bound server socket to dynamic port $boundPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to dynamic port", e)
            _state.value = TransferState.Finished(
                success = false,
                filesCount = 0,
                totalSize = 0L,
                timeElapsedSec = 0,
                averageSpeedBytesPerSec = 0,
                isSender = true,
                errorMsg = context.getString(R.string.error_sender_start)
            )
            cleanup()
            return
        }

        try {
            serverSocket = sSocket
            val port = boundPort

            _state.value = TransferState.SenderWaiting(
                code = code,
                files = resolvedFiles,
                port = port,
                localIp = localIp
            )

            // Start Foreground Service
            val initialNotifMsg = context.getString(R.string.notif_waiting_code, code)
            startForegroundService(context, initialNotifMsg)

            // Register NSD Service with the code and local dynamic port
            registerNsdService(context, code, port)

            // Accept Connections Loop
            serverJob = scope.launch {
                listenForConnections(context, sSocket, resolvedFiles)
            }

            // Monitor IP changes for display purposes without altering the code
            scope.launch {
                var previousIp = localIp
                while (isActive) {
                    delay(3000)
                    val currentState = _state.value
                    if (currentState is TransferState.SenderWaiting) {
                        val currentIpInfo = getLocalIpInfo()
                        val currentIp = currentIpInfo?.ip ?: "127.0.0.1"
                        if (currentIp != previousIp && currentIp != "127.0.0.1") {
                            Log.d(TAG, "Local IP address changed from $previousIp to $currentIp. Updating display.")
                            previousIp = currentIp
                            _state.value = currentState.copy(localIp = currentIp)
                        }
                    } else {
                        break
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sender server", e)
            _state.value = TransferState.Finished(
                success = false,
                filesCount = 0,
                totalSize = 0L,
                timeElapsedSec = 0,
                averageSpeedBytesPerSec = 0,
                isSender = true,
                errorMsg = context.getString(R.string.error_sender_start)
            )
            cleanup()
        }
    }

    private suspend fun listenForConnections(
        context: Context,
        sSocket: ServerSocket,
        initialFiles: List<TransferFile>
    ) = withContext(Dispatchers.IO) {
        val filesList = initialFiles.toMutableList()
        val totalSize = filesList.sumOf { it.size }
        totalBytesTransferred.set(0L)

        try {
            while (isActive && !sSocket.isClosed) {
                val socket = sSocket.accept()
                activeSockets.add(socket)

                scope.launch {
                    handleSenderConnection(context, socket, filesList, totalSize)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Server socket closed: ${e.message}")
        }
    }

    private suspend fun handleSenderConnection(
        context: Context,
        socket: Socket,
        filesList: MutableList<TransferFile>,
        totalSize: Long
    ) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 15000 // 15 seconds timeout
            
            // Wrap connection in encrypted streams immediately
            val crypto = getCryptoStreams(socket, currentSessionCode, isClient = false)
            val input = crypto.inputStream
            val output = crypto.outputStream

            val connectionType = input.read() // Read 1st byte for type: 1 = Control, 2 = Data

            if (connectionType == 1) {
                // Control connection: Handshake files list, but require approval first
                val deferred = CompletableDeferred<Boolean>()
                approvalDeferred = deferred

                _state.value = TransferState.SenderWaitingForApproval(
                    receiverIp = socket.inetAddress?.hostAddress ?: "Unknown IP",
                    files = filesList.toList(),
                    code = currentSessionCode,
                    port = socket.localPort,
                    localIp = getLocalIpInfo()?.ip ?: "127.0.0.1",
                    onAccept = {
                        deferred.complete(true)
                    },
                    onReject = {
                        deferred.complete(false)
                    }
                )

                // Wait up to 30 seconds for the user's manual Accept/Reject response
                val approved = try {
                    withTimeout(30000L) {
                        deferred.await()
                    }
                } catch (e: Exception) {
                    false
                } finally {
                    approvalDeferred = null
                }

                val dos = DataOutputStream(output)
                if (approved) {
                    dos.write(1) // Write "Approved" status byte (1)
                    dos.writeUTF(currentSessionId ?: "")
                    dos.writeInt(filesList.size)
                    filesList.forEach { file ->
                        dos.writeInt(file.id)
                        dos.writeUTF(file.name)
                        dos.writeLong(file.size)
                    }
                    dos.flush()

                    // Transition state to transferring once sender approves
                    _state.value = TransferState.ActiveTransfer(
                        files = filesList.toList(),
                        isSender = true,
                        speedBytesPerSec = 0,
                        totalSize = totalSize,
                        totalTransferred = 0L
                    )
                    startStatsMonitoring(true, filesList, totalSize)
                    startForegroundService(context, context.getString(R.string.notif_sending_title))
                } else {
                    dos.write(0) // Write "Rejected" status byte (0)
                    dos.flush()
                    _state.value = TransferState.Finished(
                        success = false,
                        filesCount = 0,
                        totalSize = 0L,
                        timeElapsedSec = 0,
                        averageSpeedBytesPerSec = 0,
                        isSender = true,
                        errorMsg = context.getString(R.string.error_sender_rejected)
                    )
                    cleanup()
                }
            } else if (connectionType == 2) {
                // Data connection: Stream specific file content
                val dis = DataInputStream(input)
                val fileId = dis.readInt()
                val offset = dis.readLong() // Read requested offset for resume

                val fileIndex = filesList.indexOfFirst { it.id == fileId }
                if (fileIndex != -1) {
                    val file = filesList[fileIndex]
                    val uri = senderFilesMap[fileId]

                    if (uri != null) {
                        // Mark file as transferring
                        updateFileProgress(fileId, offset, TransferStatus.TRANSFERRING)

                        val outputBuffered = BufferedOutputStream(output, 128 * 1024)

                        if (file.name.startsWith("swipe_text_share:")) {
                            val text = file.name.substringAfter("swipe_text_share:")
                            val bytes = text.toByteArray(Charsets.UTF_8)
                            
                            // Send text data
                            outputBuffered.write(bytes)
                            outputBuffered.flush()

                            // Calculate SHA-256 of text bytes
                            val digest = MessageDigest.getInstance("SHA-256")
                            digest.update(bytes)
                            val checksumBytes = digest.digest() // 32 bytes

                            // Send SHA-256 checksum
                            outputBuffered.write(checksumBytes)
                            outputBuffered.flush()

                            // Half-close
                            socket.shutdownOutput()

                            totalBytesTransferred.addAndGet(bytes.size.toLong())
                            updateFileProgress(fileId, bytes.size.toLong(), TransferStatus.COMPLETED)
                        } else {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            if (inputStream != null) {
                                val buffer = ByteArray(BUFFER_SIZE)
                                var bytesRead = 0
                                var fileSentBytes = offset
                                val digest = MessageDigest.getInstance("SHA-256")

                                inputStream.use { fis ->
                                    // Skip offset bytes for resume transfer but update digest on the fly
                                    if (offset > 0) {
                                        val skipBuffer = ByteArray(BUFFER_SIZE)
                                        var skipped = 0L
                                        while (skipped < offset) {
                                            val toRead = minOf(skipBuffer.size.toLong(), offset - skipped).toInt()
                                            val read = fis.read(skipBuffer, 0, toRead)
                                            if (read == -1) break
                                            digest.update(skipBuffer, 0, read)
                                            skipped += read
                                        }
                                    }

                                    while (isActive && !socket.isClosed && fis.read(buffer).also { bytesRead = it } != -1) {
                                        outputBuffered.write(buffer, 0, bytesRead)
                                        digest.update(buffer, 0, bytesRead)
                                        fileSentBytes += bytesRead
                                        totalBytesTransferred.addAndGet(bytesRead.toLong())
                                        updateFileProgress(fileId, fileSentBytes, TransferStatus.TRANSFERRING)
                                        pokeWakeLock()
                                    }

                                    if (fileSentBytes >= file.size) {
                                        // Write SHA-256 checksum of the entire file (32 bytes)
                                        val checksumBytes = digest.digest()
                                        outputBuffered.write(checksumBytes)
                                    }
                                    outputBuffered.flush()
                                }

                                // Half-close the socket output stream to signal receiver we are done sending!
                                try {
                                    socket.shutdownOutput()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error shutting down socket output", e)
                                }

                                if (fileSentBytes >= file.size) {
                                    updateFileProgress(fileId, file.size, TransferStatus.COMPLETED)
                                } else {
                                    updateFileProgress(fileId, fileSentBytes, TransferStatus.FAILED)
                                }
                            } else {
                                updateFileProgress(fileId, 0, TransferStatus.FAILED, context.getString(R.string.error_open_file))
                            }
                        }
                    } else {
                        updateFileProgress(fileId, 0, TransferStatus.FAILED, context.getString(R.string.error_file_not_found))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling sender connection", e)
        } finally {
            socket.closeQuietly()
            activeSockets.remove(socket)
            checkSenderCompletion(context)
        }
    }

    private suspend fun updateFileProgress(fileId: Int, bytes: Long, status: TransferStatus, error: String? = null) {
        val now = System.currentTimeMillis()
        val isFinalState = status == TransferStatus.COMPLETED || status == TransferStatus.FAILED
        if (!isFinalState) {
            val lastUpdate = lastFileProgressUpdateMap[fileId] ?: 0L
            if (now - lastUpdate < 150L) {
                return
            }
        }
        lastFileProgressUpdateMap[fileId] = now

        stateMutex.withLock {
            val currentState = _state.value
            if (currentState is TransferState.ActiveTransfer) {
                val updatedList = currentState.files.map {
                    if (it.id == fileId) {
                        it.copy(bytesTransferred = bytes, status = status, error = error)
                    } else {
                        it
                    }
                }
                val activeIndex = updatedList.indexOfFirst { it.status == TransferStatus.TRANSFERRING }.let {
                    if (it == -1) currentState.activeFileIndex else it
                }
                _state.value = currentState.copy(
                    files = updatedList,
                    totalTransferred = updatedList.sumOf { it.bytesTransferred },
                    activeFileIndex = activeIndex
                )
            }
        }
    }

    private fun checkSenderCompletion(context: Context) {
        val currentState = _state.value
        if (currentState is TransferState.ActiveTransfer) {
            val allDone = currentState.files.all { it.status == TransferStatus.COMPLETED || it.status == TransferStatus.FAILED }
            if (allDone) {
                statsJob?.cancel()
                val totalTransferred = currentState.files.sumOf { it.bytesTransferred }
                val timeElapsed = (System.currentTimeMillis() - sessionStartTimeMs) / 1000
                val elapsed = if (timeElapsed <= 0) 1 else timeElapsed
                val avgSpeed = totalTransferred / elapsed

                val successFiles = currentState.files.filter { it.status == TransferStatus.COMPLETED }
                val failedFiles = currentState.files.filter { it.status == TransferStatus.FAILED }
                val totalFilesCount = currentState.files.size
                val successCount = successFiles.size

                val success = successCount == totalFilesCount
                val isPartial = successCount > 0 && failedFiles.isNotEmpty()

                _state.value = TransferState.Finished(
                    success = success,
                    isPartial = isPartial,
                    filesCount = successCount,
                    successCount = successCount,
                    totalFilesCount = totalFilesCount,
                    totalSize = successFiles.sumOf { it.size },
                    timeElapsedSec = elapsed,
                    averageSpeedBytesPerSec = avgSpeed,
                    isSender = true
                )
                saveHistoryEntry(context, isSender = true, files = currentState.files, success = success)
                cleanup()
            }
        }
    }

    private fun saveHistoryEntry(context: Context, isSender: Boolean, files: List<TransferFile>, success: Boolean) {
        val summary = if (files.size == 1) {
            val firstFile = files.firstOrNull()
            if (firstFile != null && firstFile.name.startsWith("swipe_text_share:")) {
                context.getString(R.string.quick_shared_text)
            } else {
                firstFile?.name ?: context.getString(R.string.unknown_file)
            }
        } else {
            context.getString(R.string.summary_files_value, files.size)
        }
        val totalSize = files.sumOf { it.size }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.transferHistoryDao().insert(
                    TransferHistory(
                        isSender = isSender,
                        filesSummary = summary,
                        totalSize = totalSize,
                        success = success
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save history entry", e)
            }
        }
    }

    private fun getAvailableStorageSpace(directory: File): Long {
        return try {
            val stat = android.os.StatFs(directory.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun formatSize(context: Context, size: Long): String {
        if (size <= 0) return "0 " + context.getString(R.string.unit_bytes)
        val units = arrayOf(
            context.getString(R.string.unit_bytes),
            context.getString(R.string.unit_kb),
            context.getString(R.string.unit_mb),
            context.getString(R.string.unit_gb),
            context.getString(R.string.unit_tb)
        )
        var digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        if (digitGroups >= units.size) {
            digitGroups = units.size - 1
        }
        return String.format(java.util.Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    // --- RECEIVER (CLIENT) FLOW ---
    fun startReceiver(context: Context, code: String, manualIp: String? = null, manualPort: Int? = null) {
        lastReceiverCode = code
        lastReceiverIp = manualIp
        lastReceiverPort = manualPort
        discoveryRetryCount = 0
        cleanup()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        acquireMulticastLock(context)

        if (manualIp != null && manualPort != null) {
            _state.value = TransferState.ReceiverConnecting(code, context.getString(R.string.notif_connecting_manual, manualIp, manualPort))
            startForegroundService(context, context.getString(R.string.notif_connecting_generic))
            clientJob = scope.launch {
                try {
                    val ipAddress = withContext(Dispatchers.IO) {
                        InetAddress.getByName(manualIp)
                    }
                    runReceiverClient(context, ipAddress, manualPort)
                } catch (e: Exception) {
                    Log.e(TAG, "Manual connection failed", e)
                    _state.value = TransferState.Finished(
                        success = false,
                        filesCount = 0,
                        totalSize = 0L,
                        timeElapsedSec = 0,
                        averageSpeedBytesPerSec = 0,
                        isSender = false,
                        errorMsg = context.getString(R.string.error_manual_conn)
                    )
                    cleanup()
                }
            }
        } else {
            // Start local network discovery (NSD) using the entered 6-digit code
            _state.value = TransferState.ReceiverConnecting(code, context.getString(R.string.notif_searching_nsd))
            startForegroundService(context, context.getString(R.string.notif_search_and_connect))
            discoverNsdService(context, code)
        }
    }

    private fun discoverNsdService(context: Context, targetCode: String) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                if (discoveryRetryCount < 1) {
                    discoveryRetryCount++
                    Log.d(TAG, "Retrying NSD discovery once (attempt $discoveryRetryCount)...")
                    val currentListener = this
                    scope.launch {
                        delay(1000)
                        try {
                            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, currentListener)
                        } catch (e: Exception) {
                            Log.e(TAG, "Retry discovery failed", e)
                            failDiscovery()
                        }
                    }
                } else {
                    failDiscovery()
                }
            }

            private fun failDiscovery() {
                _state.value = TransferState.Finished(
                    success = false,
                    filesCount = 0,
                    totalSize = 0L,
                    timeElapsedSec = 0,
                    averageSpeedBytesPerSec = 0,
                    isSender = false,
                    errorMsg = context.getString(R.string.error_discovery_failed)
                )
                cleanup()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "Discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                val name = serviceInfo.serviceName
                if (name != null && name.contains(targetCode)) {
                    // Match found! Resolve service
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            Log.d(TAG, "Service resolved: IP=${resolvedInfo.host}, Port=${resolvedInfo.port}")
                            // Stop discovery once resolved successfully
                            try {
                                discoveryListener?.let { manager.stopServiceDiscovery(it) }
                            } catch (e: Exception) {}

                            // Start transfer client coroutine
                            clientJob = scope.launch {
                                runReceiverClient(context, resolvedInfo.host, resolvedInfo.port)
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "Service lost")
            }
        }

        discoveryListener = listener

        // Search with standard TCP DNS-SD
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering services", e)
            _state.value = TransferState.Finished(
                success = false,
                filesCount = 0,
                totalSize = 0L,
                timeElapsedSec = 0,
                averageSpeedBytesPerSec = 0,
                isSender = false,
                errorMsg = context.getString(R.string.error_discovery_start)
            )
        }

        // Timeout fallback after 9 seconds
        scope.launch {
            delay(9000)
            val currState = _state.value
            if (currState is TransferState.ReceiverConnecting) {
                _state.value = TransferState.Finished(
                    success = false,
                    filesCount = 0,
                    totalSize = 0L,
                    timeElapsedSec = 0,
                    averageSpeedBytesPerSec = 0,
                    isSender = false,
                    errorMsg = context.getString(R.string.error_subnet_mismatch_guidance)
                )
                cleanup()
            }
        }
    }

    private suspend fun runReceiverClient(context: Context, host: InetAddress, port: Int) = withContext(Dispatchers.IO) {
        var controlSocket: Socket? = null
        try {
            _state.value = TransferState.ReceiverConnecting("", context.getString(R.string.status_sender_found))

            controlSocket = Socket()
            controlSocket.soTimeout = 15000 // 15 seconds timeout
            controlSocket.connect(InetSocketAddress(host, port), 3500)
            activeSockets.add(controlSocket)

            val crypto = getCryptoStreams(controlSocket, lastReceiverCode ?: "", isClient = true)
            val output = crypto.outputStream
            val input = crypto.inputStream

            // Handshake Control Connection type
            output.write(1)
            output.flush()

            val dis = DataInputStream(input)
            val approvalStatus = dis.read()
            if (approvalStatus != 1) {
                _state.value = TransferState.Finished(
                    success = false,
                    filesCount = 0,
                    totalSize = 0L,
                    timeElapsedSec = 0,
                    averageSpeedBytesPerSec = 0,
                    isSender = false,
                    errorMsg = context.getString(R.string.error_transfer_rejected_by_sender)
                )
                cleanup()
                return@withContext
            }

            val sessionId = dis.readUTF()
            currentSessionId = sessionId
            val fileCount = dis.readInt()

            val receivedFiles = mutableListOf<TransferFile>()
            for (i in 0 until fileCount) {
                val id = dis.readInt()
                val name = dis.readUTF()
                val size = dis.readLong()
                receivedFiles.add(
                    TransferFile(
                        id = id,
                        name = name,
                        size = size,
                        status = TransferStatus.PENDING
                    )
                )
            }

            controlSocket.closeQuietly()
            controlSocket?.let { activeSockets.remove(it) }

            val totalSize = receivedFiles.sumOf { it.size }

            // Pre-check storage space before accepting files
            val swipeDir = getSwipeDirectory(context)
            val availableSpace = getAvailableStorageSpace(swipeDir)
            if (availableSpace < totalSize) {
                _state.value = TransferState.Finished(
                    success = false,
                    filesCount = 0,
                    totalSize = totalSize,
                    timeElapsedSec = 0,
                    averageSpeedBytesPerSec = 0,
                    isSender = false,
                    errorMsg = context.getString(R.string.error_insufficient_space, formatSize(context, totalSize), formatSize(context, availableSpace))
                )
                cleanup()
                return@withContext
            }

            totalBytesTransferred.set(0L)

            _state.value = TransferState.ActiveTransfer(
                files = receivedFiles,
                isSender = false,
                speedBytesPerSec = 0L,
                totalSize = totalSize,
                totalTransferred = 0L
            )

            startStatsMonitoring(false, receivedFiles, totalSize)
            startForegroundService(context, context.getString(R.string.notif_receiving_title))

            // Download files in parallel using coroutines with a limit of 4 concurrent socket connections
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)
            val downloadJobs = receivedFiles.map { file ->
                scope.launch {
                    semaphore.withPermit {
                        downloadSingleFile(context, host, port, file, swipeDir)
                    }
                }
            }

            // Wait for all downloads to finish
            downloadJobs.joinAll()

            // Wrap up receiver session
            checkReceiverCompletion(context, receivedFiles, totalSize)

        } catch (e: Exception) {
            Log.e(TAG, "Error in receiver client", e)
            _state.value = TransferState.Finished(
                success = false,
                filesCount = 0,
                totalSize = 0,
                timeElapsedSec = 0,
                averageSpeedBytesPerSec = 0,
                isSender = false,
                errorMsg = context.getString(R.string.error_transfer_fail)
            )
            cleanup()
        } finally {
            controlSocket?.closeQuietly()
        }
    }

    private suspend fun downloadSingleFile(
        context: Context,
        host: InetAddress,
        port: Int,
        file: TransferFile,
        swipeDir: File
    ) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        var outputStream: FileOutputStream? = null
        try {
            updateFileProgress(file.id, 0L, TransferStatus.TRANSFERRING)

            socket = Socket()
            socket.soTimeout = 15000 // 15 seconds timeout
            socket.connect(InetSocketAddress(host, port), 3500)
            activeSockets.add(socket)

            val crypto = getCryptoStreams(socket, lastReceiverCode ?: "", isClient = true)
            val out = BufferedOutputStream(crypto.outputStream, 128 * 1024)
            val input = crypto.inputStream

            if (file.name.startsWith("swipe_text_share:")) {
                // Write Data Request type, fileId, and 0L offset
                out.write(2)
                val dos = DataOutputStream(out)
                dos.writeInt(file.id)
                dos.writeLong(0L)
                dos.flush()

                // Total text bytes including 32 SHA-256 bytes is file.size + 32
                val totalExpected = file.size.toInt() + 32
                val textBytes = ByteArray(totalExpected)
                var read = 0
                while (read < textBytes.size) {
                    val r = input.read(textBytes, read, textBytes.size - read)
                    if (r == -1) break
                    read += r
                }
                
                if (read < totalExpected) {
                    updateFileProgress(file.id, 0L, TransferStatus.FAILED, context.getString(R.string.error_text_cutoff))
                    return@withContext
                }

                // Extract text bytes and checksum bytes
                val textContentBytes = textBytes.copyOfRange(0, file.size.toInt())
                val senderChecksum = textBytes.copyOfRange(file.size.toInt(), totalExpected)
                
                val textDigest = MessageDigest.getInstance("SHA-256")
                textDigest.update(textContentBytes)
                val receiverChecksum = textDigest.digest()
                
                if (!receiverChecksum.contentEquals(senderChecksum)) {
                    updateFileProgress(file.id, 0L, TransferStatus.FAILED, context.getString(R.string.error_text_md5_mismatch))
                    return@withContext
                }

                val textContent = String(textContentBytes, Charsets.UTF_8)
                ReceivedTextHolder.lastReceivedText = textContent

                // Copy received text to clipboard as convenience
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Swipe Share", textContent)
                    clipboard.setPrimaryClip(clip)
                } catch (e: Exception) {}

                updateFileProgress(file.id, file.size, TransferStatus.COMPLETED)
                return@withContext
            }

            // Standard file download with Resume Support
            val partFile = File(swipeDir, file.name + ".part")
            val sessionFile = File(swipeDir, file.name + ".part.session")
            val parentFile = partFile.parentFile
            if (parentFile != null && !parentFile.exists()) {
                parentFile.mkdirs()
            }

            var offset = 0L
            if (partFile.exists()) {
                var validSession = false
                if (sessionFile.exists()) {
                    try {
                        val savedSessionId = sessionFile.readText().trim()
                        if (savedSessionId == currentSessionId && partFile.length() <= file.size) {
                            validSession = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading session file", e)
                    }
                }
                if (validSession) {
                    offset = partFile.length()
                } else {
                    try { partFile.delete() } catch (e: Exception) {}
                    try { sessionFile.delete() } catch (e: Exception) {}
                    offset = 0L
                }
            } else {
                try { sessionFile.delete() } catch (e: Exception) {}
                offset = 0L
            }

            // If starting fresh, save the currentSessionId to the companion metadata file
            if (offset == 0L) {
                try {
                    sessionFile.writeText(currentSessionId ?: "")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write companion session file", e)
                }
            }

            // Write Data Request type, fileId, and the offset for resume
            out.write(2)
            val dos = DataOutputStream(out)
            dos.writeInt(file.id)
            dos.writeLong(offset)
            dos.flush()

            outputStream = FileOutputStream(partFile, true) // Append mode

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead = 0
            var fileReceivedBytes = offset

            outputStream.use { fos ->
                while (isActive && !socket.isClosed && input.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                    fileReceivedBytes += bytesRead
                    totalBytesTransferred.addAndGet(bytesRead.toLong())
                    updateFileProgress(file.id, fileReceivedBytes, TransferStatus.TRANSFERRING)
                    pokeWakeLock()
                }
            }

            // Close sockets and streams so we can process files safely
            socket.closeQuietly()
            socket?.let { activeSockets.remove(it) }
            socket = null
            outputStream.closeQuietly()
            outputStream = null

            val totalPartSize = partFile.length()
            val expectedTotalSize = file.size + 32
            if (totalPartSize < expectedTotalSize) {
                updateFileProgress(file.id, fileReceivedBytes, TransferStatus.FAILED, context.getString(R.string.error_file_cutoff))
            } else {
                val actualFileSize = totalPartSize - 32
                
                // Read 32 bytes of SHA-256 checksum from the end of partFile
                val senderChecksum = ByteArray(32)
                RandomAccessFile(partFile, "r").use { raf ->
                    raf.seek(actualFileSize)
                    raf.readFully(senderChecksum)
                }
                
                val receiverDigest = MessageDigest.getInstance("SHA-256")
                val finalFile = getUniqueFile(swipeDir, file.name)
                finalFile.parentFile?.mkdirs()
                
                FileInputStream(partFile).use { fis ->
                    FileOutputStream(finalFile).use { fos ->
                        val copyBuffer = ByteArray(BUFFER_SIZE)
                        var remaining = actualFileSize
                        while (remaining > 0) {
                            val toRead = minOf(copyBuffer.size.toLong(), remaining).toInt()
                            val read = fis.read(copyBuffer, 0, toRead)
                            if (read == -1) break
                            fos.write(copyBuffer, 0, read)
                            receiverDigest.update(copyBuffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                
                val receiverChecksum = receiverDigest.digest()
                if (receiverChecksum.contentEquals(senderChecksum)) {
                    // SHA-256 matches! Perfect success!
                    updateFileProgress(file.id, file.size, TransferStatus.COMPLETED)
                    try { partFile.delete() } catch (e: Exception) {}
                    try { sessionFile.delete() } catch (e: Exception) {}
                } else {
                    // Checksum mismatch, file is corrupted!
                    try { finalFile.delete() } catch (e: Exception) {}
                    updateFileProgress(file.id, fileReceivedBytes - 32, TransferStatus.FAILED, context.getString(R.string.error_file_corrupted))
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file: ${file.name}", e)
            updateFileProgress(file.id, 0, TransferStatus.FAILED, context.getString(R.string.error_transfer_fail))
        } finally {
            socket?.closeQuietly()
            socket?.let { activeSockets.remove(it) }
            outputStream?.closeQuietly()
        }
    }

    private fun checkReceiverCompletion(context: Context, initialFiles: List<TransferFile>, totalSize: Long) {
        val currentState = _state.value
        if (currentState is TransferState.ActiveTransfer) {
            statsJob?.cancel()
            val totalTransferred = currentState.files.sumOf { it.bytesTransferred }
            val timeElapsed = (System.currentTimeMillis() - sessionStartTimeMs) / 1000
            val elapsed = if (timeElapsed <= 0) 1 else timeElapsed
            val avgSpeed = totalTransferred / elapsed

            val successFiles = currentState.files.filter { it.status == TransferStatus.COMPLETED }
            val failedFiles = currentState.files.filter { it.status == TransferStatus.FAILED }
            val totalFilesCount = currentState.files.size
            val successCount = successFiles.size

            val success = successCount == totalFilesCount
            val isPartial = successCount > 0 && failedFiles.isNotEmpty()

            _state.value = TransferState.Finished(
                success = success,
                isPartial = isPartial,
                filesCount = successCount,
                successCount = successCount,
                totalFilesCount = totalFilesCount,
                totalSize = successFiles.sumOf { it.size },
                timeElapsedSec = elapsed,
                averageSpeedBytesPerSec = avgSpeed,
                isSender = false,
                errorMsg = if (successCount == 0) context.getString(R.string.error_no_valid_received) else null
            )
            saveHistoryEntry(context, isSender = false, files = currentState.files, success = success)
            cleanup()
        }
    }

    // --- STATISTICS & MONITORING ---
    private fun startStatsMonitoring(isSender: Boolean, files: List<TransferFile>, totalSize: Long) {
        statsJob?.cancel()
        lastBytesTransferred = 0L
        lastTimeMs = System.currentTimeMillis()
        sessionStartTimeMs = System.currentTimeMillis()

        statsJob = scope.launch {
            while (isActive) {
                delay(800)
                val currentBytes = totalBytesTransferred.get()
                val now = System.currentTimeMillis()
                val deltaBytes = currentBytes - lastBytesTransferred
                val deltaTimeMs = now - lastTimeMs

                val speed = if (deltaTimeMs > 0) {
                    (deltaBytes * 1000) / deltaTimeMs
                } else {
                    0L
                }

                // Smooth speed updates in StateFlow
                val currState = _state.value
                if (currState is TransferState.ActiveTransfer) {
                    _state.value = currState.copy(
                        speedBytesPerSec = speed,
                        totalTransferred = currentBytes
                    )
                }

                lastBytesTransferred = currentBytes
                lastTimeMs = now
            }
        }
    }

    // --- SERVICE HANDLERS ---
    private fun startForegroundService(context: Context, statusMessage: String) {
        try {
            val intent = Intent(context, FileTransferService::class.java).apply {
                putExtra("status_message", statusMessage)
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    // --- NSD CONFIGS ---
    private fun markNsdRegistrationFailed() {
        // Non-fatal: the server socket keeps listening and manual IP connection
        // still works, we just surface a hint in the UI instead of hanging silently.
        val currentState = _state.value
        if (currentState is TransferState.SenderWaiting) {
            _state.value = currentState.copy(nsdRegistrationFailed = true)
        }
    }

    private fun registerNsdService(context: Context, code: String, port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "Swipe_$code"
            setPort(port)
        }

        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD Service registered successfully: ${NsdServiceInfo.serviceName}")
                registrationRetryCount = 0
                // Clear any earlier failure hint now that broadcasting is confirmed working.
                val currentState = _state.value
                if (currentState is TransferState.SenderWaiting && currentState.nsdRegistrationFailed) {
                    _state.value = currentState.copy(nsdRegistrationFailed = false)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "NSD Registration failed: $errorCode")
                if (registrationRetryCount < 1 && _state.value is TransferState.SenderWaiting) {
                    registrationRetryCount++
                    Log.d(TAG, "Retrying NSD registration once (attempt $registrationRetryCount)...")
                    scope.launch {
                        delay(1000)
                        if (_state.value is TransferState.SenderWaiting) {
                            try {
                                registerNsdService(context, code, port)
                            } catch (e: Exception) {
                                Log.e(TAG, "Retry registration failed", e)
                                markNsdRegistrationFailed()
                            }
                        }
                    }
                } else {
                    markNsdRegistrationFailed()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                Log.d(TAG, "NSD Service unregistered successfully")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "NSD Unregistration failed: $errorCode")
            }
        }

        registrationListener = listener

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering NSD service", e)
            markNsdRegistrationFailed()
        }
    }

    // --- SYSTEM & STORAGE UTILITIES ---
    fun getSwipeDirectory(context: Context? = null): File {
        // Primary directory: Public Downloads/Swipe
        val downloadSwipeDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Swipe")
        try {
            if (!downloadSwipeDir.exists()) {
                downloadSwipeDir.mkdirs()
            }
            if (downloadSwipeDir.exists() && downloadSwipeDir.canWrite()) {
                return downloadSwipeDir
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to use public Download/Swipe directory, falling back", e)
        }

        // Fallback: App-specific external directory or filesDir to ensure safety on all Android versions
        val ctx = context ?: appContext
        if (ctx != null) {
            val appSpecificDir = ctx.getExternalFilesDir(null)
            val fallbackDir = if (appSpecificDir != null) {
                File(appSpecificDir, "Swipe")
            } else {
                File(ctx.filesDir, "Swipe")
            }
            if (!fallbackDir.exists()) {
                fallbackDir.mkdirs()
            }
            return fallbackDir
        }

        // Secondary fallback to legacy external storage root
        val legacyDir = File(Environment.getExternalStorageDirectory(), "Swipe")
        if (!legacyDir.exists()) {
            legacyDir.mkdirs()
        }
        return legacyDir
    }

    fun getUniqueFile(directory: File, fileName: String): File {
        var finalDir = directory
        val pathParts = fileName.split("/")
        if (pathParts.size > 1) {
            val subdirs = pathParts.dropLast(1).joinToString("/")
            finalDir = File(directory, subdirs)
            if (!finalDir.exists()) {
                finalDir.mkdirs()
            }
        }
        val actualName = pathParts.last()

        var file = File(finalDir, actualName)
        if (!file.exists()) return file

        val nameWithoutExtension = file.nameWithoutExtension
        val extension = file.extension
        val extSuffix = if (extension.isNotEmpty()) ".$extension" else ""

        var counter = 1
        while (file.exists()) {
            file = File(finalDir, "$nameWithoutExtension ($counter)$extSuffix")
            counter++
        }
        return file
    }

    private fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "unknown_file"
        var size = 0L
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "unknown_file"
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
            } else if (uri.scheme == "file") {
                val file = File(uri.path ?: "")
                name = file.name
                size = file.length()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying file info", e)
        }
        return Pair(name, size)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in Collections.list(interfaces)) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting local IP address", ex)
        }
        return null
    }

    // --- CANCELLATION & CLEANUP ---
    fun cancelTransfer() {
        Log.d(TAG, "Cancelling active file transfer session")
        _state.value = TransferState.Idle
        cleanup(deleteUnfinishedParts = true)
    }

    fun retryTransfer(context: Context, isSender: Boolean) {
        cleanup()
        if (isSender) {
            val uris = lastSenderUris
            if (uris.isNotEmpty()) {
                startSender(context, uris)
            } else {
                Toast.makeText(context, context.getString(R.string.toast_no_files_retry), Toast.LENGTH_SHORT).show()
            }
        } else {
            val code = lastReceiverCode ?: ""
            val manualIp = lastReceiverIp
            val manualPort = lastReceiverPort
            if (code.isNotEmpty() || !manualIp.isNullOrEmpty()) {
                startReceiver(context, code, manualIp, manualPort)
            } else {
                Toast.makeText(context, context.getString(R.string.toast_no_retry_details), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun cleanup(deleteUnfinishedParts: Boolean = false) {
        // Cancel stats monitor
        statsJob?.cancel()
        statsJob = null

        // Cancel core server/client jobs
        serverJob?.cancel()
        serverJob = null

        clientJob?.cancel()
        clientJob = null

        scope.cancel()

        // Close all active sockets
        activeSockets.forEach {
            it.closeQuietly()
        }
        activeSockets.clear()

        // Close Server Socket
        serverSocket?.closeQuietly()
        serverSocket = null

        // Unregister NSD Service (registration and discovery are stopped independently
        // so that a failure tearing down one doesn't leave the other leaked/active).
        try {
            nsdManager?.let { manager ->
                registrationListener?.let { listener ->
                    manager.unregisterService(listener)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NSD registration", e)
        }
        try {
            nsdManager?.let { manager ->
                discoveryListener?.let { listener ->
                    manager.stopServiceDiscovery(listener)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop NSD discovery", e)
        }
        nsdManager = null
        registrationListener = null
        discoveryListener = null

        releaseMulticastLock()
        senderFilesMap.clear()
        lastFileProgressUpdateMap.clear()
        totalBytesTransferred.set(0L)

        if (deleteUnfinishedParts) {
            try {
                val swipeDir = getSwipeDirectory()
                if (swipeDir.exists()) {
                    swipeDir.listFiles()?.forEach { file ->
                        if (file.name.endsWith(".part") || file.name.endsWith(".part.session")) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean unfinished part files", e)
            }
        }
    }

    // --- ENCRYPTION HELPER FUNCTIONS ---
    class CryptoStreams(
        val inputStream: InputStream,
        val outputStream: OutputStream
    )

    private fun getEncryptionKey(code: String): SecretKeySpec {
        val saltPhrase = if (code.isBlank()) "SwipeFallbackSaltPhrase" else code
        val salt = saltPhrase.toByteArray(Charsets.UTF_8)
        
        val algorithm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "PBKDF2WithHmacSHA256"
        } else {
            "PBKDF2WithHmacSHA1"
        }

        return try {
            val secretKeyFactory = javax.crypto.SecretKeyFactory.getInstance(algorithm)
            val spec = javax.crypto.spec.PBEKeySpec(
                code.toCharArray(),
                salt,
                65536, // Increased iterations for better security
                256   // 256 bits AES key
            )
            val keyBytes = secretKeyFactory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } catch (e: Exception) {
            Log.e(TAG, "PBKDF2 key generation failed, falling back to SHA-256", e)
            val digest = MessageDigest.getInstance("SHA-256")
            val keyBytes = digest.digest(salt)
            SecretKeySpec(keyBytes, "AES")
        }
    }

    fun getCryptoStreams(socket: Socket, code: String, isClient: Boolean): CryptoStreams {
        val key = getEncryptionKey(code)
        val rawIn = socket.getInputStream()
        val rawOut = socket.getOutputStream()
        
        val clientToHostIv = ByteArray(16)
        val hostToClientIv = ByteArray(16)
        
        if (isClient) {
            val secureRandom = java.security.SecureRandom()
            secureRandom.nextBytes(clientToHostIv)
            secureRandom.nextBytes(hostToClientIv)
            rawOut.write(clientToHostIv)
            rawOut.write(hostToClientIv)
            rawOut.flush()
        } else {
            var readBytes = 0
            while (readBytes < 16) {
                val r = rawIn.read(clientToHostIv, readBytes, 16 - readBytes)
                if (r == -1) throw java.io.IOException("Socket closed while reading clientToHostIv")
                readBytes += r
            }
            readBytes = 0
            while (readBytes < 16) {
                val r = rawIn.read(hostToClientIv, readBytes, 16 - readBytes)
                if (r == -1) throw java.io.IOException("Socket closed while reading hostToClientIv")
                readBytes += r
            }
        }
        
        val encryptIv = if (isClient) clientToHostIv else hostToClientIv
        val decryptIv = if (isClient) hostToClientIv else clientToHostIv
        
        val encryptCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(encryptIv))
        }
        val decryptCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, IvParameterSpec(decryptIv))
        }
        
        return CryptoStreams(
            CipherInputStream(rawIn, decryptCipher),
            CipherOutputStream(rawOut, encryptCipher)
        )
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (e: Exception) {}
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (e: Exception) {}
    }
}

object ReceivedTextHolder {
    var lastReceivedText: String? = null
}

data class UriWithPath(val uri: Uri, val relativePath: String)

fun collectFilesFromTree(context: Context, dir: DocumentFile, currentPath: String, result: MutableList<UriWithPath>) {
    val files = try { dir.listFiles() } catch (e: Exception) { emptyArray() }
    val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"
    for (file in files) {
        val name = file.name ?: continue
        if (file.isDirectory) {
            collectFilesFromTree(context, file, "$prefix$name", result)
        } else if (file.isFile) {
            result.add(UriWithPath(file.uri, "$prefix$name"))
        }
    }
}
