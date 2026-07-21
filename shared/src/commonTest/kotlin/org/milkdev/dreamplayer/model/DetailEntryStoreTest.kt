package org.milkdev.dreamplayer.model

import org.milkdev.dreamplayer.library.UserPlaylist
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.NavigationEntry
import org.milkdev.dreamplayer.playback.LibraryUiState
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
    fun activeDetailUpdateRequiresMatchingUiAndNavigationTokens() {
        val state = LibraryUiState(activeDetailEntryId = 10L)

        val updated = state.updateForActiveDetail(
            expectedEntryId = 10L,
            currentContentEntryId = 10L,
        ) { it.copy(error = "updated") }
        val staleNavigation = state.updateForActiveDetail(
            expectedEntryId = 10L,
            currentContentEntryId = 11L,
        ) { it.copy(error = "wrong") }
        val staleUi = state.copy(activeDetailEntryId = 11L).updateForActiveDetail(
            expectedEntryId = 10L,
            currentContentEntryId = 10L,
        ) { it.copy(error = "wrong") }

        assertEquals("updated", updated.error)
        assertSame(state, staleNavigation)
        assertEquals(null, staleUi.error)
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

    private companion object {
        const val PlaylistId = 5L
    }
}
