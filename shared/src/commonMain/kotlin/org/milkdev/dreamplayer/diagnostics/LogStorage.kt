package org.milkdev.dreamplayer.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object LogStorage {
    private const val MAX_LOGS = 1000

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastFmNetworkLogs = MutableStateFlow<List<String>>(emptyList())
    val lastFmNetworkLogs: StateFlow<List<String>> = _lastFmNetworkLogs.asStateFlow()

    private val _otherNetworkLogs = MutableStateFlow<List<String>>(emptyList())
    val otherNetworkLogs: StateFlow<List<String>> = _otherNetworkLogs.asStateFlow()

    fun addLog(message: String) {
        _logs.addBounded(message)
    }

    fun addNetworkLog(
        channel: NetworkTraceLogChannel,
        message: String,
    ) {
        when (channel) {
            NetworkTraceLogChannel.LastFm -> _lastFmNetworkLogs.addBounded(message)
            NetworkTraceLogChannel.OtherServices -> _otherNetworkLogs.addBounded(message)
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

    private fun MutableStateFlow<List<String>>.addBounded(message: String) {
        update { currentLogs ->
            (currentLogs + message).takeLast(MAX_LOGS)
        }
    }
}
