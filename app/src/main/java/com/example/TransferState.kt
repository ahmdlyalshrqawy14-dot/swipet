package com.example

import android.net.Uri

enum class TransferStatus {
    PENDING,
    TRANSFERRING,
    COMPLETED,
    FAILED
}

data class TransferFile(
    val id: Int,
    val name: String,
    val size: Long,
    val uriString: String? = null,
    val bytesTransferred: Long = 0,
    val status: TransferStatus = TransferStatus.PENDING,
    val error: String? = null
) {
    val progress: Float
        get() = if (size > 0) bytesTransferred.toFloat() / size else 0f
    
    val uri: Uri?
        get() = uriString?.let { Uri.parse(it) }
}

sealed class TransferState {
    object Idle : TransferState()
    
    data class SenderWaiting(
        val code: String,
        val files: List<TransferFile>,
        val port: Int,
        val localIp: String,
        val nsdRegistrationFailed: Boolean = false
    ) : TransferState()

    data class SenderWaitingForApproval(
        val receiverIp: String,
        val files: List<TransferFile>,
        val code: String,
        val port: Int,
        val localIp: String,
        val onAccept: () -> Unit,
        val onReject: () -> Unit
    ) : TransferState()
    
    data class ReceiverConnecting(
        val code: String,
        val statusMessage: String
    ) : TransferState()
    
    data class ActiveTransfer(
        val files: List<TransferFile>,
        val isSender: Boolean,
        val speedBytesPerSec: Long,
        val totalSize: Long,
        val totalTransferred: Long,
        val activeFileIndex: Int = 0
    ) : TransferState()
    
    data class Finished(
        val success: Boolean,
        val filesCount: Int,
        val totalSize: Long,
        val timeElapsedSec: Long,
        val averageSpeedBytesPerSec: Long,
        val isSender: Boolean,
        val errorMsg: String? = null,
        val isPartial: Boolean = false,
        val successCount: Int = 0,
        val totalFilesCount: Int = 0
    ) : TransferState()
}
