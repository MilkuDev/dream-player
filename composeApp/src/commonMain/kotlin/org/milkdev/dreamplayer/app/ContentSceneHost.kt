package org.milkdev.dreamplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Composable
internal fun ContentSceneHost(
    scene: ContentSceneSnapshot,
    committedScene: ContentSceneSnapshot,
    backSession: ContentBackSession?,
    chromeLayers: ContentChromeLayers,
    persistentPadding: PaddingValues,
    navigationChrome: @Composable (NavigationChromePresentation, HazeState) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val retainedPadding = remember(scene.currentEntry.entryId) { persistentPadding }
    val currentPersistentPadding = if (
        backSession == null &&
        committedScene.currentEntry.entryId == scene.currentEntry.entryId
    ) {
        persistentPadding
    } else {
        retainedPadding
    }
    val destinationChrome = chromeLayers.destination
        ?.takeIf { it.entryId == scene.currentEntry.entryId }
        ?.chrome
    val destinationHazeState = rememberHazeState()
    val isPredictiveOrigin = backSession
        ?.takeIf { it.mode == ContentBackMode.Predictive }
        ?.origin
        ?.currentEntry
        ?.entryId == scene.currentEntry.entryId

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isPredictiveOrigin) {
                    Modifier.clip(RoundedCornerShape(28.dp))
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (backSession != null) {
                    Modifier.clearAndSetSemantics { }
                } else {
                    Modifier
                },
            ),
    ) {
        if (destinationChrome != null) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    navigationChrome(destinationChrome, destinationHazeState)
                },
            ) { destinationPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(destinationHazeState),
                ) {
                    content(destinationPadding)
                }
            }
        } else {
            content(currentPersistentPadding)
        }
    }
}
