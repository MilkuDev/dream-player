package org.milkdev.dreamplayer.model

import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.library.LibraryCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DetailEntryStoreTest {
    @Test
    fun descriptorIsRegisteredByEntryIdentity() {
        val store = DetailEntryStore()
        val entry = playlistEntry(entryId = 10L)
        val descriptor = playlistDescriptor()

        store.register(entry, descriptor)

        assertEquals(descriptor, store.descriptorFor(entry))
        assertTrue(store.contains(entry.entryId))
    }

    @Test
    fun descriptorRouteMustMatchEntryRoute() {
        val store = DetailEntryStore()
        val entry = playlistEntry(entryId = 10L)
        val otherDescriptor = PlaylistDetailDescriptor(
            playlist = playlistDescriptor().playlist.copy(id = 20L),
        )

        assertFailsWith<IllegalArgumentException> {
            store.register(entry, otherDescriptor)
        }
    }

    @Test
    fun equalRoutesInDifferentEntriesKeepIndependentDescriptors() {
        val store = DetailEntryStore()
        val first = playlistEntry(entryId = 10L)
        val second = playlistEntry(entryId = 11L)

        store.register(first, playlistDescriptor(name = "First snapshot"))
        store.register(second, playlistDescriptor(name = "Second snapshot"))

        assertEquals("First snapshot", (store.descriptorFor(first) as PlaylistDetailDescriptor).playlist.name)
        assertEquals("Second snapshot", (store.descriptorFor(second) as PlaylistDetailDescriptor).playlist.name)
    }

    @Test
    fun retainEntriesRemovesOnlyDescriptorsOutsideCommittedStack() {
        val store = DetailEntryStore()
        val retained = playlistEntry(entryId = 10L)
        val removed = playlistEntry(entryId = 11L)
        store.register(retained, playlistDescriptor())
        store.register(removed, playlistDescriptor())

        store.retainEntries(listOf(retained))

        assertEquals(1, store.size)
        assertTrue(store.contains(retained.entryId))
        assertFalse(store.contains(removed.entryId))
    }

    @Test
    fun activeDetailUpdateRequiresMatchingPresentationAndNavigationTokens() {
        val originalEntry = collectionState("Original")
        val state = DetailPresentationState().activate(10L, originalEntry)

        val updated = state.updateActiveEntry(
            expectedEntryId = 10L,
            currentContentEntryId = 10L,
        ) { collectionState("Updated") }
        val staleNavigation = state.updateActiveEntry(
            expectedEntryId = 10L,
            currentContentEntryId = 11L,
        ) { collectionState("Wrong") }
        val stalePresentation = state.copy(activeEntryId = 11L).updateActiveEntry(
            expectedEntryId = 10L,
            currentContentEntryId = 10L,
        ) { collectionState("Wrong") }

        assertEquals(collectionState("Updated"), updated.entry(10L))
        assertSame(state, staleNavigation)
        assertSame(state.entries[10L], stalePresentation.entries[10L])
    }

    @Test
    fun genreStateSurvivesAlbumPushPreviewCancelAndCommitReactivation() {
        val genre = collectionState("Loaded genre")
        val album = collectionState("Loaded album")
        val genreEntryId = 10L
        val albumEntryId = 11L

        val genreActive = DetailPresentationState().activate(genreEntryId, genre)
        val albumActive = genreActive.activate(albumEntryId, album)

        assertSame(genre, albumActive.entry(genreEntryId))
        assertEquals(albumEntryId, albumActive.activeEntryId)

        val predictivePreview = albumActive.entry(genreEntryId)
        assertSame(genre, predictivePreview)
        assertEquals(albumEntryId, albumActive.activeEntryId)

        val afterCancel = albumActive
        assertSame(albumActive, afterCancel)

        val afterCommit = albumActive.activate(
            entryId = genreEntryId,
            initialState = collectionState("Empty replacement", isLoading = true),
        )
        assertEquals(genreEntryId, afterCommit.activeEntryId)
        assertSame(genre, afterCommit.entry(genreEntryId))
        assertSame(album, afterCommit.entry(albumEntryId))

        val afterTransitionSettled = afterCommit.retainEntries(setOf(genreEntryId))
        assertEquals(genreEntryId, afterTransitionSettled.activeEntryId)
        assertSame(genre, afterTransitionSettled.entry(genreEntryId))
        assertEquals(null, afterTransitionSettled.entry(albumEntryId))
    }

    @Test
    fun presentationEntriesArePrunedOnlyByExplicitRetainedSet() {
        val state = DetailPresentationState()
            .activate(10L, collectionState("Genre"))
            .activate(11L, collectionState("Album"))

        val retained = state.retainEntries(setOf(10L))

        assertEquals(setOf(10L), retained.entries.keys)
        assertEquals(null, retained.activeEntryId)
    }

    private fun playlistEntry(entryId: Long): NavigationEntry {
        return NavigationEntry(
            entryId = entryId,
            route = AppRoute.Playlist(playlistId = PlaylistId),
        )
    }

    private fun playlistDescriptor(name: String = "Playlist"): PlaylistDetailDescriptor {
        return PlaylistDetailDescriptor(
            playlist = UserPlaylist(
                id = PlaylistId,
                name = name,
                createdAt = 1L,
            ),
        )
    }

    private fun collectionState(
        title: String,
        isLoading: Boolean = false,
    ): LibraryCollectionDetailUiState {
        return LibraryCollectionDetailUiState(
            collection = LibraryCollectionDetailsUiModel(
                type = LibraryCollectionType.GENRE,
                title = title,
                subtitle = "Subtitle",
                artworkUri = null,
                tracks = emptyList(),
            ),
            isLoading = isLoading,
        )
    }

    private companion object {
        const val PlaylistId = 5L
    }
}
