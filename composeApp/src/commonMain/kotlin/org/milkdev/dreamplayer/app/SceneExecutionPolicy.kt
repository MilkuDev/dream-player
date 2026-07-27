package org.milkdev.dreamplayer.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

internal enum class ScenePresentationRole {
    Active,
    Retained,
    Preview,
    Origin,
    Entering,
    Exiting,
}

internal enum class ForegroundOwner {
    Content,
    Player,
    Queue,
}

internal sealed interface ForegroundPresentation {
    data class Settled(
        val owner: ForegroundOwner,
    ) : ForegroundPresentation

    data class Transitioning(
        val from: ForegroundOwner,
        val to: ForegroundOwner,
        val token: Long,
    ) : ForegroundPresentation
}

@Immutable
internal data class SceneExecutionPolicy(
    val allowsInputAndSemantics: Boolean,
    val allowsPagingDemand: Boolean,
    val allowsImperativeScroll: Boolean,
    val allowsDiagnosticCollection: Boolean,
    val allowsUncachedArtwork: Boolean,
    val allowsFocusAndPopups: Boolean,
    val authorityEpoch: Long,
) {
    fun restricted(): SceneExecutionPolicy = copy(
        allowsInputAndSemantics = false,
        allowsPagingDemand = false,
        allowsImperativeScroll = false,
        allowsDiagnosticCollection = false,
        allowsUncachedArtwork = false,
        allowsFocusAndPopups = false,
    )

    companion object {
        val CompatibilityActive = SceneExecutionPolicy(
            allowsInputAndSemantics = true,
            allowsPagingDemand = true,
            allowsImperativeScroll = true,
            allowsDiagnosticCollection = true,
            allowsUncachedArtwork = true,
            allowsFocusAndPopups = true,
            authorityEpoch = 0L,
        )
    }
}

internal fun resolveSceneExecutionPolicy(
    role: ScenePresentationRole,
    foregroundPresentation: ForegroundPresentation,
    contentTransitionSettled: Boolean,
    isAuthoritativeContentEntry: Boolean,
    isSelectedRootTab: Boolean = true,
    authorityEpoch: Long,
): SceneExecutionPolicy {
    val hasAuthority =
        role == ScenePresentationRole.Active &&
            foregroundPresentation == ForegroundPresentation.Settled(ForegroundOwner.Content) &&
            contentTransitionSettled &&
            isAuthoritativeContentEntry &&
            isSelectedRootTab
    return SceneExecutionPolicy(
        allowsInputAndSemantics = hasAuthority,
        allowsPagingDemand = hasAuthority,
        allowsImperativeScroll = hasAuthority,
        allowsDiagnosticCollection = hasAuthority,
        allowsUncachedArtwork = hasAuthority,
        allowsFocusAndPopups = hasAuthority,
        authorityEpoch = authorityEpoch,
    )
}

internal val LocalSceneExecutionPolicy = staticCompositionLocalOf {
    SceneExecutionPolicy.CompatibilityActive
}
