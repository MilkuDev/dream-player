package org.milkdev.dreamplayer.app

import org.milkdev.dreamplayer.navigation.AppNavigationState
import org.milkdev.dreamplayer.navigation.AppRoute
import org.milkdev.dreamplayer.navigation.MainTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlobalChromePresentationTest {
    @Test
    fun mainDestinationUsesOnePresentedNavigationDock() {
        val presentation = resolveGlobalChromePresentation(
            AppNavigationState().selectMainTab(MainTab.Library),
        )

        assertTrue(presentation.isDockPresented)
        assertEquals(
            MainTab.Library,
            assertIs<GlobalDockContent.Navigation>(presentation.dockContent).activeMainTab,
        )
    }

    @Test
    fun searchDockIdentitySurvivesPlaybackOverlay() {
        val search = AppNavigationState()
            .selectMainTab(MainTab.Library)
            .openSearch()
        val searchEntryId = search.currentEntry.entryId
        val presentation = resolveGlobalChromePresentation(
            search.push(AppRoute.Player).push(AppRoute.Queue),
        )

        assertTrue(presentation.isDockPresented)
        assertEquals(
            searchEntryId,
            assertIs<GlobalDockContent.Search>(presentation.dockContent).entryId,
        )
    }

    @Test
    fun settingsSuppressesDockWithoutReplacingItsRetainedContent() {
        val search = AppNavigationState().openSearch()
        val searchEntryId = search.currentEntry.entryId
        val presentation = resolveGlobalChromePresentation(
            search.push(AppRoute.Settings),
        )

        assertFalse(presentation.isDockPresented)
        assertEquals(
            searchEntryId,
            assertIs<GlobalDockContent.Search>(presentation.dockContent).entryId,
        )
    }

    @Test
    fun chromeAuthorityRequiresSettledContentForeground() {
        val active = resolveGlobalChromeExecutionPolicy(
            foregroundPresentation = ForegroundPresentation.Settled(ForegroundOwner.Content),
            contentPresentationSettled = true,
            isDockPresented = true,
            authorityEpoch = 7L,
        )
        val transitioning = resolveGlobalChromeExecutionPolicy(
            foregroundPresentation = ForegroundPresentation.Transitioning(
                from = ForegroundOwner.Content,
                to = ForegroundOwner.Player,
                token = 8L,
            ),
            contentPresentationSettled = true,
            isDockPresented = true,
            authorityEpoch = 8L,
        )
        val settings = resolveGlobalChromeExecutionPolicy(
            foregroundPresentation = ForegroundPresentation.Settled(ForegroundOwner.Content),
            contentPresentationSettled = true,
            isDockPresented = false,
            authorityEpoch = 9L,
        )

        assertTrue(active.allowsMiniPlayerInput)
        assertTrue(active.allowsDockInput)
        assertFalse(transitioning.allowsMiniPlayerInput)
        assertFalse(transitioning.allowsDockInput)
        assertTrue(settings.allowsMiniPlayerInput)
        assertFalse(settings.allowsDockInput)
        assertFalse(settings.allowsSearchFocus)
    }

    @Test
    fun predictiveDockRevealTracksGestureInBothDirections() {
        assertEquals(
            0.35f,
            predictiveDockPresenceProgress(
                originPresented = false,
                previewPresented = true,
                progress = 0.35f,
            ),
        )
        assertEquals(
            0.65f,
            predictiveDockPresenceProgress(
                originPresented = true,
                previewPresented = false,
                progress = 0.35f,
            ),
        )
        assertEquals(
            null,
            predictiveDockPresenceProgress(
                originPresented = true,
                previewPresented = true,
                progress = 0.35f,
            ),
        )
    }
}
