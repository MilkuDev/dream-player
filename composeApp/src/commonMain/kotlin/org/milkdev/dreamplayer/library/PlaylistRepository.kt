package org.milkdev.dreamplayer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.database.SystemPlaylistDefinition
import org.milkdev.dreamplayer.database.SystemPlaylists
import org.milkdev.dreamplayer.database.appDatabase
import org.milkdev.dreamplayer.database.entities.PlaylistEntity
import org.milkdev.dreamplayer.database.entities.PlaylistTrackCrossRef
import org.milkdev.dreamplayer.database.entities.TrackEntity
import kotlin.random.Random

data class UserPlaylist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val isSystem: Boolean = false,
    val canEditTracks: Boolean = true,
    val canRename: Boolean = true,
)

data class PreparedPlaybackQueue(
    val tracks: List<LibraryTrack>,
    val startIndex: Int,
)

enum class ShuffleAnchor {
    StartFromFirstTrack,
    KeepSelectedTrackFirst,
}

object PlaylistRepository {
    private val playlistDao by lazy { appDatabase.playlistDao() }

    val visiblePlaylists: Flow<List<UserPlaylist>>
        get() = playlistDao.getVisiblePlaylists(SystemPlaylists.VisibleInLibrary.map { it.id })
            .map { playlists -> playlists.map { it.toUserPlaylist() } }

    fun tracksForPlaylist(playlistId: Long): Flow<List<LibraryTrack>> {
        return playlistDao.getTracksForPlaylist(playlistId)
            .map { tracks -> tracks.map { it.toLibraryTrack() } }
    }

    suspend fun createUserPlaylist(name: String): Long? = withContext(Dispatchers.IO) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            null
        } else {
            playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = trimmedName,
                    isSystem = false,
                    editable = true,
                )
            )
        }
    }

    suspend fun ensureSystemPlaylists() = withContext(Dispatchers.IO) {
        SystemPlaylists.All.forEach { systemPlaylist ->
            ensureSystemPlaylistExists(systemPlaylist)
        }
    }

    suspend fun addTrackToFavorites(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        ensureSystemPlaylistExists(SystemPlaylists.Favorites)

        val currentTrackIds = playlistDao.getTrackIdsForPlaylist(SystemPlaylists.Favorites.id)
        if (trackId in currentTrackIds) {
            false
        } else {
            replacePlaylistTracks(
                playlistId = SystemPlaylists.Favorites.id,
                trackIds = currentTrackIds + trackId,
            )
            true
        }
    }

    suspend fun removeTrackFromFavorites(trackId: Long): Boolean = withContext(Dispatchers.IO) {
        val currentTrackIds = playlistDao.getTrackIdsForPlaylist(SystemPlaylists.Favorites.id)
        if (trackId in currentTrackIds) {
            replacePlaylistTracks(
                playlistId = SystemPlaylists.Favorites.id,
                trackIds = currentTrackIds - trackId, // Удаляем ID из списка
            )
            true
        } else {
            false
        }
    }

    fun observeIsFavorite(trackId: Long): Flow<Boolean> {
        return tracksForPlaylist(SystemPlaylists.Favorites.id).map { tracks ->
            tracks.any { it.id == trackId }
        }
    }

    suspend fun replacePlaylistTracks(
        playlistId: Long,
        trackIds: List<Long>,
    ) = withContext(Dispatchers.IO) {
        val systemPlaylist = SystemPlaylists.byId(playlistId)
        if (systemPlaylist != null && !systemPlaylist.permissions.canEditTracks) {
            return@withContext
        }

        val crossRefs = trackIds
            .distinct()
            .mapIndexed { index, trackId ->
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                    position = index,
                )
            }

        playlistDao.replacePlaylistTracks(playlistId, crossRefs)
    }

    fun prepareShuffledQueue(
        tracks: List<LibraryTrack>,
        selectedTrackId: Long? = null,
        anchor: ShuffleAnchor = ShuffleAnchor.StartFromFirstTrack,
        random: Random = Random.Default,
    ): PreparedPlaybackQueue? {
        if (tracks.isEmpty()) return null

        val shuffledTracks = when (anchor) {
            ShuffleAnchor.StartFromFirstTrack -> tracks.shuffled(random)
            ShuffleAnchor.KeepSelectedTrackFirst -> {
                val selectedTrack = selectedTrackId?.let { trackId ->
                    tracks.firstOrNull { it.id == trackId }
                }

                if (selectedTrack == null) {
                    tracks.shuffled(random)
                } else {
                    val remainingTracks = tracks
                        .filterNot { it.id == selectedTrack.id }
                        .shuffled(random)

                    buildList(tracks.size) {
                        add(selectedTrack)
                        addAll(remainingTracks)
                    }
                }
            }
        }

        return PreparedPlaybackQueue(
            tracks = shuffledTracks,
            startIndex = 0,
        )
    }

    private suspend fun ensureSystemPlaylistExists(systemPlaylist: SystemPlaylistDefinition) {
        val existingPlaylist = playlistDao.getPlaylistById(systemPlaylist.id)
        if (existingPlaylist == null) {
            playlistDao.upsertPlaylist(
                systemPlaylist.toEntity()
            )
            return
        }

        val normalizedPlaylist = systemPlaylist.toEntity(existingPlaylist)
        if (existingPlaylist != normalizedPlaylist) {
            playlistDao.upsertPlaylist(
                normalizedPlaylist
            )
        }
    }
}

private fun PlaylistEntity.toUserPlaylist(): UserPlaylist {
    val systemPlaylist = SystemPlaylists.byId(id)
    return UserPlaylist(
        id = id,
        name = if (systemPlaylist != null && !systemPlaylist.permissions.canRename) {
            systemPlaylist.name
        } else {
            name
        },
        createdAt = createdAt,
        isSystem = systemPlaylist != null || isSystem,
        canEditTracks = systemPlaylist?.permissions?.canEditTracks ?: editable,
        canRename = systemPlaylist?.permissions?.canRename ?: true,
    )
}

private fun SystemPlaylistDefinition.toEntity(existingPlaylist: PlaylistEntity? = null): PlaylistEntity {
    val playlistName = if (permissions.canRename && existingPlaylist != null) {
        existingPlaylist.name
    } else {
        name
    }

    return PlaylistEntity(
        id = id,
        name = playlistName,
        createdAt = existingPlaylist?.createdAt ?: createdAt,
        isSystem = true,
        editable = permissions.canEditTracks,
    )
}

private fun TrackEntity.toLibraryTrack(): LibraryTrack {
    return LibraryTrack(
        id = id,
        title = title,
        artistName = artistName,
        albumName = albumName,
        durationMs = durationMs,
        albumArtUri = albumArtUri,
        isPresent = isPresent,
    )
}
