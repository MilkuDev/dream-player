package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.library.SettingsRepository

internal class PlaybackPersistenceCoordinator(
    scope: CoroutineScope,
    private val persistState: suspend (SettingsRepository.SavedPlaybackState) -> Unit =
        SettingsRepository::savePlaybackState,
    private val persistPosition: suspend (Long, Long) -> Unit =
        SettingsRepository::saveTrackPositionOnly,
    private val clearPersistedState: suspend () -> Unit =
        SettingsRepository::clearPlaybackState,
) {
    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                if (command is Command.Flush) {
                    command.completion.complete(Unit)
                    continue
                }

                try {
                    when (command) {
                        is Command.SaveState -> persistState(command.state)
                        is Command.SavePosition -> persistPosition(
                            command.trackId,
                            command.positionMs,
                        )
                        Command.Clear -> clearPersistedState()
                        is Command.Flush -> Unit
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppDebugLog.log(
                        "playback_persistence_error command=${command.label} " +
                            "message=${error.message.orEmpty()}"
                    )
                }
            }
        }
    }

    fun saveState(state: SettingsRepository.SavedPlaybackState) {
        enqueue(Command.SaveState(state))
    }

    fun savePosition(trackId: Long, positionMs: Long) {
        enqueue(
            Command.SavePosition(
                trackId = trackId,
                positionMs = positionMs.coerceAtLeast(0L),
            )
        )
    }

    fun clear() {
        enqueue(Command.Clear)
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.Flush(completion))
        completion.await()
    }

    private fun enqueue(command: Command) {
        if (commands.trySend(command).isFailure) {
            AppDebugLog.log("playback_persistence_enqueue_failed command=${command.label}")
        }
    }

    private sealed interface Command {
        val label: String

        data class SaveState(
            val state: SettingsRepository.SavedPlaybackState,
        ) : Command {
            override val label: String = "save_state"
        }

        data class SavePosition(
            val trackId: Long,
            val positionMs: Long,
        ) : Command {
            override val label: String = "save_position"
        }

        data object Clear : Command {
            override val label: String = "clear"
        }

        data class Flush(
            val completion: CompletableDeferred<Unit>,
        ) : Command {
            override val label: String = "flush"
        }
    }
}
