package com.kneejar.tvhue

import kotlinx.coroutines.flow.MutableStateFlow

enum class Mode { SCREEN, MOOD, RECONNECTING }

sealed class SyncStatus {
    object Off : SyncStatus()
    object Starting : SyncStatus()
    data class Running(val mode: Mode, val detail: String = "") : SyncStatus()
    data class Error(val message: String) : SyncStatus()

    val isActive: Boolean get() = this is Starting || this is Running
}

/** Shared between the sync service and the screen so the button always shows the real state. */
object SyncState {
    val status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
}
