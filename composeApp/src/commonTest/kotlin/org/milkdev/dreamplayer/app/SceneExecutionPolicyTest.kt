package org.milkdev.dreamplayer.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SceneExecutionPolicyTest {
    @Test
    fun onlySettledAuthoritativeActiveContentReceivesExecutionAuthority() {
        val active = resolveSceneExecutionPolicy(
            role = ScenePresentationRole.Active,
            foregroundPresentation =
                ForegroundPresentation.Settled(ForegroundOwner.Content),
            contentTransitionSettled = true,
            isAuthoritativeContentEntry = true,
            authorityEpoch = 7L,
        )

        assertTrue(active.allowsInputAndSemantics)
        assertTrue(active.allowsPagingDemand)
        assertTrue(active.allowsImperativeScroll)
        assertTrue(active.allowsDiagnosticCollection)
        assertTrue(active.allowsUncachedArtwork)
        assertTrue(active.allowsFocusAndPopups)
    }

    @Test
    fun previewAndRetainedScenesCannotPerformWork() {
        listOf(
            ScenePresentationRole.Preview,
            ScenePresentationRole.Retained,
            ScenePresentationRole.Origin,
            ScenePresentationRole.Entering,
            ScenePresentationRole.Exiting,
        ).forEach { role ->
            val policy = resolveSceneExecutionPolicy(
                role = role,
                foregroundPresentation =
                    ForegroundPresentation.Settled(ForegroundOwner.Content),
                contentTransitionSettled = true,
                isAuthoritativeContentEntry = true,
                authorityEpoch = 3L,
            )

            assertFalse(policy.allowsInputAndSemantics, "role=$role")
            assertFalse(policy.allowsPagingDemand, "role=$role")
            assertFalse(policy.allowsImperativeScroll, "role=$role")
            assertFalse(policy.allowsDiagnosticCollection, "role=$role")
            assertFalse(policy.allowsUncachedArtwork, "role=$role")
            assertFalse(policy.allowsFocusAndPopups, "role=$role")
        }
    }

    @Test
    fun overlaysTransitionsAndOffscreenTabsRevokeContentAuthority() {
        val cases = listOf(
            Triple(
                ForegroundPresentation.Settled(ForegroundOwner.Player),
                true,
                true,
            ),
            Triple(
                ForegroundPresentation.Settled(ForegroundOwner.Queue),
                true,
                true,
            ),
            Triple(
                ForegroundPresentation.Transitioning(
                    from = ForegroundOwner.Content,
                    to = ForegroundOwner.Player,
                    token = 1L,
                ),
                true,
                true,
            ),
            Triple(
                ForegroundPresentation.Settled(ForegroundOwner.Content),
                false,
                true,
            ),
            Triple(
                ForegroundPresentation.Settled(ForegroundOwner.Content),
                true,
                false,
            ),
        )

        cases.forEach { (foreground, transitionSettled, selectedTab) ->
            val policy = resolveSceneExecutionPolicy(
                role = ScenePresentationRole.Active,
                foregroundPresentation = foreground,
                contentTransitionSettled = transitionSettled,
                isAuthoritativeContentEntry = true,
                isSelectedRootTab = selectedTab,
                authorityEpoch = 9L,
            )

            assertFalse(policy.allowsPagingDemand)
            assertFalse(policy.allowsFocusAndPopups)
        }
    }
}
