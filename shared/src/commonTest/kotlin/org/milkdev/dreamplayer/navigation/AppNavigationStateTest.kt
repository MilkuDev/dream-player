package org.milkdev.dreamplayer.navigation

import org.milkdev.dreamplayer.library.LibraryCollectionType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppNavigationStateTest {
    @Test
    fun initialStateContainsOnlyHome() {
        val state = AppNavigationState()

        assertRoutes(state, AppRoute.Home)
        assertEquals(AppRoute.Home, state.currentDestination)
        assertEquals(AppRoute.Home, state.currentContentEntry.route)
        assertEquals(MainDestination.Home, state.activeMainDestination)
        assertFalse(state.canNavigateBack)
        assertNull(state.pop())
    }

    @Test
    fun selectingLibraryBuildsHomeLibraryStack() {
        val state = AppNavigationState().selectMainPage(MainPage.Library)

        assertRoutes(state, AppRoute.Home, AppRoute.Library)
        assertEquals(MainDestination.Library, state.activeMainDestination)
        assertTrue(state.canNavigateBack)
    }

    @Test
    fun backFromLibraryReturnsHome() {
        val state = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .pop()

        assertNotNull(state)
        assertRoutes(state, AppRoute.Home)
    }

    @Test
    fun selectingMainPageDropsDetailsAndOverlays() {
        val state = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Genre5)
            .push(Album42)
            .push(AppRoute.Player)
            .push(AppRoute.Queue)

        val library = state.selectMainPage(MainPage.Library)
        val home = state.selectMainPage(MainPage.Home)

        assertRoutes(library, AppRoute.Home, AppRoute.Library)
        assertRoutes(home, AppRoute.Home)
    }

    @Test
    fun homeAlwaysRemainsFirstEntry() {
        val states = listOf(
            AppNavigationState(),
            AppNavigationState().selectMainPage(MainPage.Library),
            AppNavigationState().openSearch(),
            AppNavigationState().push(Playlist10).push(AppRoute.Player),
        )

        states.forEach { state ->
            assertEquals(AppRoute.Home, state.backStack.first().route)
            assertEquals(0L, state.backStack.first().entryId)
        }
    }

    @Test
    fun mainPageEntriesKeepStableIdentity() {
        val initial = AppNavigationState()
        val firstLibraryId = initial
            .selectMainPage(MainPage.Library)
            .backStack.last().entryId
        val secondLibraryId = initial
            .selectMainPage(MainPage.Library)
            .selectMainPage(MainPage.Home)
            .selectMainPage(MainPage.Library)
            .backStack.last().entryId

        assertEquals(0L, initial.backStack.first().entryId)
        assertEquals(firstLibraryId, secondLibraryId)
        assertNotEquals(0L, firstLibraryId)
    }

    @Test
    fun consecutiveExactRouteDoesNotCreateEntry() {
        val state = AppNavigationState().push(Artist7)

        assertSame(state, state.push(Artist7))
    }

    @Test
    fun repeatedRouteSeparatedByAnotherRouteGetsUniqueEntry() {
        val state = AppNavigationState()
            .push(Artist7)
            .push(Album42)
            .push(Artist7)

        val artistEntries = state.backStack.filter { it.route == Artist7 }
        assertEquals(2, artistEntries.size)
        assertNotEquals(artistEntries[0].entryId, artistEntries[1].entryId)
    }

    @Test
    fun differentPlaylistRoutesGetUniqueEntries() {
        val state = AppNavigationState()
            .push(Playlist10)
            .push(Playlist20)

        assertNotEquals(state.backStack[1].entryId, state.backStack[2].entryId)
    }

    @Test
    fun popRestoresExistingEntryIdentity() {
        val artistState = AppNavigationState().push(Artist7)
        val artistEntry = artistState.currentEntry
        val restored = artistState.push(Album42).pop()

        assertNotNull(restored)
        assertEquals(artistEntry, restored.currentEntry)
    }

    @Test
    fun searchFromHomeReturnsToHome() {
        val search = AppNavigationState().openSearch()

        assertRoutes(search, AppRoute.Home, AppRoute.Search)
        assertEquals(MainDestination.Search, search.activeMainDestination)
        assertRoutes(search.pop(), AppRoute.Home)
    }

    @Test
    fun searchFromLibraryReturnsToLibrary() {
        val search = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .openSearch()

        assertRoutes(search, AppRoute.Home, AppRoute.Library, AppRoute.Search)
        assertEquals(MainDestination.Search, search.activeMainDestination)
        assertRoutes(search.pop(), AppRoute.Home, AppRoute.Library)
    }

    @Test
    fun searchFromHomeDetailDropsDetailSuffix() {
        val search = AppNavigationState()
            .push(Playlist10)
            .openSearch()

        assertRoutes(search, AppRoute.Home, AppRoute.Search)
    }

    @Test
    fun searchFromLibraryDetailsDropsDetailSuffix() {
        val search = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Genre5)
            .push(Album42)
            .openSearch()

        assertRoutes(search, AppRoute.Home, AppRoute.Library, AppRoute.Search)
    }

    @Test
    fun reopeningSearchFromItsDetailKeepsOriginalSearchEntry() {
        val search = AppNavigationState().openSearch()
        val searchEntry = search.currentEntry
        val reopened = search
            .push(Artist7)
            .openSearch()

        assertRoutes(reopened, AppRoute.Home, AppRoute.Search)
        assertEquals(searchEntry, reopened.currentEntry)
    }

    @Test
    fun closingSearchRemovesItsWholeSuffixAndKeepsAnchor() {
        val homeSearchDetail = AppNavigationState()
            .openSearch()
            .push(Artist7)
        val librarySearchDetail = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .openSearch()
            .push(Playlist10)

        assertRoutes(homeSearchDetail.closeSearch(), AppRoute.Home)
        assertRoutes(
            librarySearchDetail.closeSearch(),
            AppRoute.Home,
            AppRoute.Library,
        )
        val initial = AppNavigationState()
        assertSame(initial, initial.closeSearch())
    }

    @Test
    fun genericPushDoesNotBypassMainNavigationSemantics() {
        val state = AppNavigationState()

        assertSame(state, state.push(AppRoute.Home))
        assertSame(state, state.push(AppRoute.Library))
        assertSame(state, state.push(AppRoute.Search))
    }

    @Test
    fun detailHistoryPreservesEveryEntry() {
        val state = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Genre5)
            .push(Album42)
            .push(Artist7)

        assertRoutes(
            state,
            AppRoute.Home,
            AppRoute.Library,
            Genre5,
            Album42,
            Artist7,
        )
        assertEquals(MainDestination.Library, state.activeMainDestination)
    }

    @Test
    fun backFromAlbumRestoresGenreWithoutSkippingIt() {
        val album = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Genre5)
            .push(Album42)
        val genreEntry = album.backStack[2]

        val genre = album.pop()

        assertNotNull(genre)
        assertRoutes(genre, AppRoute.Home, AppRoute.Library, Genre5)
        assertEquals(genreEntry, genre.currentEntry)
    }

    @Test
    fun guardedBackRejectsAStaleAnimatedCommit() {
        val genre = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Genre5)
        val staleGenreEntryId = genre.currentEntry.entryId
        val album = genre.push(Album42)

        assertNull(album.pop(expectedTopEntryId = staleGenreEntryId))
        assertRoutes(
            album.pop(expectedTopEntryId = album.currentEntry.entryId),
            AppRoute.Home,
            AppRoute.Library,
            Genre5,
        )
    }

    @Test
    fun detailInheritsSearchAsActiveMainDestination() {
        val state = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .openSearch()
            .push(Artist7)
            .push(Playlist10)

        assertEquals(MainDestination.Search, state.activeMainDestination)
        assertEquals(Playlist10, state.currentContentEntry.route)
    }

    @Test
    fun settingsPreservesItsMainPageOrigin() {
        val homeSettings = AppNavigationState().push(AppRoute.Settings)
        val librarySettings = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(AppRoute.Settings)

        assertRoutes(homeSettings, AppRoute.Home, AppRoute.Settings)
        assertEquals(MainDestination.Home, homeSettings.activeMainDestination)
        assertRoutes(librarySettings, AppRoute.Home, AppRoute.Library, AppRoute.Settings)
        assertEquals(MainDestination.Library, librarySettings.activeMainDestination)
    }

    @Test
    fun aiDebugRequiresSettingsAsImmediatePredecessor() {
        val initial = AppNavigationState()
        val settings = initial.push(AppRoute.Settings)

        assertSame(initial, initial.push(AppRoute.AiDebugSettings))
        assertRoutes(
            settings.push(AppRoute.AiDebugSettings),
            AppRoute.Home,
            AppRoute.Settings,
            AppRoute.AiDebugSettings,
        )
    }

    @Test
    fun queueRequiresPlayerAsTopEntry() {
        val initial = AppNavigationState()
        val player = initial.push(AppRoute.Player)

        assertSame(initial, initial.push(AppRoute.Queue))
        assertRoutes(player.push(AppRoute.Queue), AppRoute.Home, AppRoute.Player, AppRoute.Queue)
    }

    @Test
    fun playerAndQueueCloseSequentially() {
        val queue = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(AppRoute.Player)
            .push(AppRoute.Queue)

        val player = queue.pop()
        val library = player?.pop()

        assertRoutes(player, AppRoute.Home, AppRoute.Library, AppRoute.Player)
        assertRoutes(library, AppRoute.Home, AppRoute.Library)
    }

    @Test
    fun overlaysDoNotChangeContentOrMainDestination() {
        val detail = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Album42)
        val contentEntry = detail.currentContentEntry
        val queue = detail
            .push(AppRoute.Player)
            .push(AppRoute.Queue)

        assertEquals(contentEntry, queue.currentContentEntry)
        assertEquals(MainDestination.Library, queue.activeMainDestination)
    }

    @Test
    fun openingPlayerWhileQueueIsOpenDoesNotChangeSuffix() {
        val queue = AppNavigationState()
            .push(AppRoute.Player)
            .push(AppRoute.Queue)

        assertSame(queue, queue.push(AppRoute.Player))
    }

    @Test
    fun contentCannotBePushedAbovePlaybackOverlay() {
        val player = AppNavigationState().push(AppRoute.Player)

        assertSame(player, player.push(Album42))
        assertSame(player, player.push(AppRoute.Settings))
    }

    @Test
    fun removingPlaybackOverlaysPreservesContentHistory() {
        val state = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Album42)
            .push(AppRoute.Player)
            .push(AppRoute.Queue)

        val content = state.removePlaybackOverlays()

        assertRoutes(content, AppRoute.Home, AppRoute.Library, Album42)
        assertSame(content, content.removePlaybackOverlays())
    }

    @Test
    fun previewBackMatchesPopWithoutMutatingSource() {
        val state = AppNavigationState()
            .selectMainPage(MainPage.Library)
            .push(Album42)
        val sourceEntries = state.backStack.toList()
        val preview = state.previewBack()
        val committed = state.pop()

        assertNotNull(preview)
        assertNotNull(committed)
        assertContentEquals(committed.backStack, preview.backStack)
        assertContentEquals(sourceEntries, state.backStack)
    }

    @Test
    fun previewBackDoesNotConsumeEntryId() {
        val state = AppNavigationState().push(Artist7)
        val preview = state.previewBack()
        val committed = state.pop()

        assertNotNull(preview)
        assertNotNull(committed)
        val previewNext = preview.push(Playlist20).currentEntry.entryId
        val committedNext = committed.push(Playlist20).currentEntry.entryId

        assertEquals(committedNext, previewNext)
    }

    @Test
    fun previewBackIsUnavailableAtRoot() {
        assertNull(AppNavigationState().previewBack())
    }

    private fun assertRoutes(
        state: AppNavigationState?,
        vararg expected: AppRoute,
    ) {
        assertNotNull(state)
        assertContentEquals(
            expected = expected.toList(),
            actual = state.backStack.map { it.route },
        )
    }

    private companion object {
        val Playlist10 = AppRoute.Playlist(playlistId = 10L)
        val Playlist20 = AppRoute.Playlist(playlistId = 20L)
        val Genre5 = AppRoute.LibraryCollection(
            type = LibraryCollectionType.GENRE,
            collectionId = 5L,
        )
        val Album42 = AppRoute.LibraryCollection(
            type = LibraryCollectionType.ALBUM,
            collectionId = 42L,
        )
        val Artist7 = AppRoute.LibraryCollection(
            type = LibraryCollectionType.ARTIST,
            collectionId = 7L,
        )
    }
}
