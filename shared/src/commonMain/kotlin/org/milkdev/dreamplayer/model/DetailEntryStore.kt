package org.milkdev.dreamplayer.model

import org.milkdev.dreamplayer.library.LibraryCollectionType
import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationEntry

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

internal fun DetailPresentationState.activate(
    entryId: Long,
    initialState: DetailEntryUiState,
): DetailPresentationState {
    return copy(
        activeEntryId = entryId,
        entries = if (entryId in entries) {
            entries
        } else {
            entries + (entryId to initialState)
        },
    )
}

internal fun DetailPresentationState.deactivate(): DetailPresentationState {
    return if (activeEntryId == null) this else copy(activeEntryId = null)
}

internal fun DetailPresentationState.updateActiveEntry(
    expectedEntryId: Long,
    currentContentEntryId: Long,
    transform: (DetailEntryUiState) -> DetailEntryUiState,
): DetailPresentationState {
    return if (
        activeEntryId == expectedEntryId &&
        currentContentEntryId == expectedEntryId
    ) {
        val currentEntry = entries[expectedEntryId] ?: return this
        copy(entries = entries + (expectedEntryId to transform(currentEntry)))
    } else {
        this
    }
}

internal fun DetailPresentationState.mapEntries(
    transform: (DetailEntryUiState) -> DetailEntryUiState,
): DetailPresentationState {
    val updatedEntries = entries.mapValues { (_, entry) -> transform(entry) }
    return if (updatedEntries == entries) this else copy(entries = updatedEntries)
}

internal fun DetailPresentationState.retainEntries(
    retainedEntryIds: Set<Long>,
): DetailPresentationState {
    val retainedEntries = entries.filterKeys(retainedEntryIds::contains)
    val retainedActiveEntryId = activeEntryId?.takeIf(retainedEntryIds::contains)
    return if (
        retainedEntries == entries &&
        retainedActiveEntryId == activeEntryId
    ) {
        this
    } else {
        copy(
            activeEntryId = retainedActiveEntryId,
            entries = retainedEntries,
        )
    }
}
