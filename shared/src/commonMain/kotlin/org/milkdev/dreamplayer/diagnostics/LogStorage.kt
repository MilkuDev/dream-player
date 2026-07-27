package org.milkdev.dreamplayer.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

data class LogEntry(
    val sequenceId: Long,
    val message: String,
    val kind: LogEntryKind = LogEntryKind.General,
) {
    override fun toString(): String = message
}

enum class LogEntryKind {
    General,
    NetworkRequest,
    NetworkResponse,
    NetworkFailure,
    NetworkCancelled,
}

@OptIn(ExperimentalAtomicApi::class)
object LogStorage {
    private const val MAX_LOGS = 1000
    private val nextSequenceId = AtomicLong(1L)

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _lastFmNetworkLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val lastFmNetworkLogs: StateFlow<List<LogEntry>> = _lastFmNetworkLogs.asStateFlow()

    private val _otherNetworkLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val otherNetworkLogs: StateFlow<List<LogEntry>> = _otherNetworkLogs.asStateFlow()

    fun addLog(message: String) {
        _logs.addBounded(message, LogEntryKind.General)
    }

    fun addNetworkLog(
        channel: NetworkTraceLogChannel,
        message: String,
        kind: LogEntryKind,
    ) {
        when (channel) {
            NetworkTraceLogChannel.LastFm -> _lastFmNetworkLogs.addBounded(message, kind)
            NetworkTraceLogChannel.OtherServices -> _otherNetworkLogs.addBounded(message, kind)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun clearLastFmNetwork() {
        _lastFmNetworkLogs.value = emptyList()
    }

    fun clearOtherNetwork() {
        _otherNetworkLogs.value = emptyList()
    }

    private fun MutableStateFlow<List<LogEntry>>.addBounded(
        message: String,
        kind: LogEntryKind,
    ) {
        val entry = LogEntry(
            sequenceId = nextSequenceId.fetchAndAdd(1L),
            message = message,
            kind = kind,
        )
        update { currentLogs ->
            (currentLogs + entry).takeLast(MAX_LOGS)
        }
    }
}
