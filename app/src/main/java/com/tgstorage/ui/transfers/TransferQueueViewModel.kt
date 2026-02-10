package com.tgstorage.ui.transfers

import androidx.lifecycle.ViewModel
import com.tgstorage.data.transfer.TransferManager
import com.tgstorage.data.transfer.TransferProgress
import com.tgstorage.data.transfer.TransferStatus
import com.tgstorage.data.transfer.TransferType
import kotlinx.coroutines.flow.StateFlow

class TransferQueueViewModel : ViewModel() {

    val transfers: StateFlow<List<TransferProgress>> = TransferManager.transfers

    val hasActiveTransfers: Boolean
        get() = TransferManager.hasActiveTransfers()

    fun cancelTransfer(fileId: Long, type: TransferType = TransferType.UPLOAD) {
        TransferManager.cancelTransfer(fileId, type)
    }

    fun clearFinished() {
        TransferManager.clearFinished()
    }

    fun isActive(progress: TransferProgress): Boolean =
        progress.status == TransferStatus.IN_PROGRESS ||
            progress.status == TransferStatus.PENDING ||
            progress.status == TransferStatus.PAUSED
}
