package org.milkdev.dreamplayer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
internal fun ContentSceneHost(
    executionPolicy: SceneExecutionPolicy = SceneExecutionPolicy.CompatibilityActive,
    contentPadding: PaddingValues = PaddingValues.Zero,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    CompositionLocalProvider(LocalSceneExecutionPolicy provides executionPolicy) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (!executionPolicy.allowsInputAndSemantics) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ),
        ) {
            content(contentPadding)
        }
    }
}
