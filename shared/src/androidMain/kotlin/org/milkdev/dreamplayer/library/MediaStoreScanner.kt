package org.milkdev.dreamplayer.library

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.FileNotFoundException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.app.applicationContext

class MediaStoreScanner : MusicScanner {

    override fun scan(): Flow<RawTrackData> = flow {
        val collection =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.VOLUME_NAME,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val cursor = applicationContext.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            null
        )

        if (cursor == null) {
            return@flow
        }

        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val volumeNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(collection, id).toString()

                val albumId = cursor.getLong(albumIdColumn)
                val volumeName = cursor.getString(volumeNameColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: MediaStore.VOLUME_EXTERNAL
                val albumUri = if (albumId > 0L) {
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Albums.getContentUri(volumeName),
                        albumId
                    ).toString()
                } else {
                    contentUri
                }

                emit(
                    RawTrackData(
                        path = contentUri,
                        mediaStoreId = id,
                        title = cursor.getString(titleColumn),
                        artist = cursor.getString(artistColumn),
                        album = cursor.getString(albumColumn),
                        albumArtist = cursor.getString(albumArtistColumn),
                        durationMs = cursor.getLong(durationColumn),
                        fileSize = cursor.getLong(sizeColumn),
                        lastModified = cursor.getLong(dateModifiedColumn),
                        albumArtworkKey = albumUri,
                        localArtworkState = LocalArtworkState.UNKNOWN,
                    )
                )
            }
        }
    }

    override suspend fun probeArtwork(candidate: ArtworkProbeCandidate): ArtworkProbeResult =
        withContext(Dispatchers.IO) {
            var sawUnknown = false
            val albumUris = candidate.albumUris.distinct()
            val sampleUris = candidate.sampleTrackUris.distinct().take(MAX_SAMPLE_TRACKS)

            for (uri in albumUris) {
                when (probeUri(uri)) {
                    ProbeOutcome.PRESENT -> return@withContext ArtworkProbeResult(
                        requestId = candidate.requestId,
                        state = LocalArtworkState.PRESENT,
                        artworkUri = uri,
                    )
                    ProbeOutcome.UNKNOWN -> sawUnknown = true
                    ProbeOutcome.MISSING -> Unit
                }
            }

            for (uri in sampleUris) {
                when (probeUri(uri)) {
                    ProbeOutcome.PRESENT -> return@withContext ArtworkProbeResult(
                        requestId = candidate.requestId,
                        state = LocalArtworkState.PRESENT,
                        artworkUri = uri,
                    )
                    ProbeOutcome.UNKNOWN -> sawUnknown = true
                    ProbeOutcome.MISSING -> Unit
                }
            }

            ArtworkProbeResult(
                requestId = candidate.requestId,
                state = if (sawUnknown) LocalArtworkState.UNKNOWN else LocalArtworkState.MISSING,
            )
        }

    override fun observeChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }

        applicationContext.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )

        awaitClose {
            applicationContext.contentResolver.unregisterContentObserver(observer)
        }
    }

    private fun probeUri(value: String): ProbeOutcome {
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull()
            ?: return ProbeOutcome.UNKNOWN
        val options = Bundle().apply {
            putParcelable(ContentResolver.EXTRA_SIZE, Point(PROBE_SIZE_PX, PROBE_SIZE_PX))
        }

        return try {
            val exists = applicationContext.contentResolver
                .openTypedAssetFileDescriptor(uri, "image/*", options)
                ?.use { true }
                ?: false
            if (exists) ProbeOutcome.PRESENT else ProbeOutcome.MISSING
        } catch (_: FileNotFoundException) {
            ProbeOutcome.MISSING
        } catch (_: SecurityException) {
            ProbeOutcome.UNKNOWN
        } catch (_: Exception) {
            ProbeOutcome.UNKNOWN
        }
    }

    private enum class ProbeOutcome {
        PRESENT,
        MISSING,
        UNKNOWN,
    }

    private companion object {
        const val PROBE_SIZE_PX = 96
        const val MAX_SAMPLE_TRACKS = 3
    }
}
