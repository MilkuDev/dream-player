package org.milkdev.dreamplayer.model

import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.playback.LibraryUiState

internal sealed interface DetailEntryDescriptor {
    val route: AppRoute
}

internal data class PlaylistDetailDescriptor(
    val playlist: UserPlaylist,
) : DetailEntryDescriptor {
    override val route: AppRoute = AppRoute.Playlist(playlist.id)
}

internal data class LibraryCollectionDetailDescriptor(
    val type: LibraryCollectionType,
    val collectionId: Long,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
) : DetailEntryDescriptor {
    override val route: AppRoute = AppRoute.LibraryCollection(
        type = type,
        collectionId = collectionId,
    )
}

internal class DetailEntryStore {
    private val descriptors = mutableMapOf<Long, DetailEntryDescriptor>()

    fun register(
        entry: NavigationEntry,
        descriptor: DetailEntryDescriptor,
    ) {
        require(entry.route == descriptor.route) {
            "Detail descriptor route must match its navigation entry"
        }
        descriptors[entry.entryId] = descriptor
    }

    fun descriptorFor(entry: NavigationEntry): DetailEntryDescriptor? {
        return descriptors[entry.entryId]?.takeIf { it.route == entry.route }
    }

    fun retainEntries(entries: List<NavigationEntry>) {
        val retainedIds = entries.mapTo(mutableSetOf()) { it.entryId }
        descriptors.keys.toList().forEach { entryId ->
            if (entryId !in retainedIds) {
                descriptors.remove(entryId)
            }
        }
    }

    fun contains(entryId: Long): Boolean {
        return entryId in descriptors
    }

    val size: Int
        get() = descriptors.size
}

internal inline fun LibraryUiState.updateForActiveDetail(
    expectedEntryId: Long,
    currentContentEntryId: Long,
    transform: (LibraryUiState) -> LibraryUiState,
): LibraryUiState {
    return if (
        activeDetailEntryId == expectedEntryId &&
        currentContentEntryId == expectedEntryId
    ) {
        transform(this)
    } else {
        this
    }
}
