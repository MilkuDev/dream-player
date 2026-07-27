package org.milkdev.dreamplayer.diagnostics

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLoggingTest {

    @Test
    fun networkRequestsAreRoutedToIndependentTerminals() {
        LogStorage.clearLastFmNetwork()
        LogStorage.clearOtherNetwork()

        val lastFmTrace = NetworkTraceLog.request(
            serviceName = "Last.fm",
            method = "GET",
            url = "https://ws.audioscrobbler.com/2.0/",
        )
        val musicBrainzTrace = NetworkTraceLog.request(
            serviceName = "MusicBrainz",
            method = "GET",
            url = "https://musicbrainz.org/ws/2/release-group",
        )

        val lastFmLogs = LogStorage.lastFmNetworkLogs.value
        val otherLogs = LogStorage.otherNetworkLogs.value
        assertTrue(lastFmLogs.single().message.contains("[#${lastFmTrace.id}] Last.fm -> GET"))
        assertFalse(lastFmLogs.single().message.contains("MusicBrainz"))
        assertTrue(otherLogs.single().message.contains("[#${musicBrainzTrace.id}] MusicBrainz -> GET"))
        assertFalse(otherLogs.single().message.contains("Last.fm"))
        assertTrue(lastFmLogs.single().kind == LogEntryKind.NetworkRequest)
        assertTrue(otherLogs.single().kind == LogEntryKind.NetworkRequest)

        LogStorage.clearLastFmNetwork()
        LogStorage.clearOtherNetwork()
    }

    @Test
    fun metadataEventsAreStructuredAndRedactSensitiveFields() {
        LogStorage.clear()

        MetadataDebugLog.event(
            "Metadata Sync Result",
            "albumId" to 42,
            "result" to "failed",
            "api_key" to "FIELD_SECRET",
            "details" to "request failed token=INLINE_SECRET",
        )

        val trace = LogStorage.logs.value.joinToString("\n")
        assertTrue(trace.contains("[Metadata][metadata_sync_result]"))
        assertTrue(trace.contains("albumId=42"))
        assertTrue(trace.contains("result=failed"))
        assertTrue(trace.contains("api_key=***"))
        assertFalse(trace.contains("FIELD_SECRET"))
        assertFalse(trace.contains("INLINE_SECRET"))
        LogStorage.clear()
    }

    @Test
    fun networkTraceRedactsSecretsFromEveryInputChannel() {
        LogStorage.clearOtherNetwork()

        val requestTrace = NetworkTraceLog.request(
            serviceName = "MusicBrainz",
            method = "GET",
            url = "https://musicbrainz.org/ws/2/?api_key=URL_SECRET&token=URL_TOKEN",
            parameters = listOf(
                "client_secret" to "PARAM_SECRET",
                "query" to "releasegroup:test",
            ),
        )
        NetworkTraceLog.textResponse(
            trace = requestTrace,
            statusCode = 200,
            body = """
                {
                  "token": "BODY_SECRET",
                  "nested": {"api_key": "NESTED_SECRET"},
                  "message": "authorization: Bearer MESSAGE_SECRET"
                }
            """.trimIndent(),
        )
        NetworkTraceLog.metadataResponse(
            trace = requestTrace,
            statusCode = 302,
            note = "location=https://example.com/cover?access_token=REDIRECT_SECRET",
        )
        NetworkTraceLog.failure(
            trace = requestTrace,
            message = "GET https://example.com/?signature=FAILURE_SECRET authorization=Bearer AUTH_SECRET",
        )

        val trace = LogStorage.otherNetworkLogs.value.joinToString("\n")
        listOf(
            "URL_SECRET",
            "URL_TOKEN",
            "PARAM_SECRET",
            "BODY_SECRET",
            "NESTED_SECRET",
            "MESSAGE_SECRET",
            "REDIRECT_SECRET",
            "FAILURE_SECRET",
            "AUTH_SECRET",
        ).forEach { secret ->
            assertFalse(trace.contains(secret), "Trace leaked $secret")
        }
        assertTrue(trace.contains("***"))
        assertTrue(trace.contains("query=releasegroup:test"))
        assertTrue(trace.contains("[#${requestTrace.id}] MusicBrainz -> GET"))
        assertTrue(trace.contains("[#${requestTrace.id}] MusicBrainz <- HTTP 200 durationMs="))
        assertTrue(trace.contains("[#${requestTrace.id}] MusicBrainz !! durationMs="))
        assertTrue(
            LogStorage.otherNetworkLogs.value.map { it.kind }.containsAll(
                listOf(
                    LogEntryKind.NetworkRequest,
                    LogEntryKind.NetworkResponse,
                    LogEntryKind.NetworkFailure,
                )
            )
        )
        LogStorage.clearOtherNetwork()
    }
}
