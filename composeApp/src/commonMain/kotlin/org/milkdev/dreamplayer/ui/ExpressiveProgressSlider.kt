/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Extracted and adapted from
 * androidx.compose.material3.
 * for DreamPlayer. Stripped of all Modifier.Node, Stroke, Color, DrawScope,
 * animation, semantics, and Material3 infrastructure. Pure geometry only.
 */

package org.milkdev.dreamplayer.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
data class PlayerSliderColors(
    val waveColor: Color,
    val trackColor: Color,
    val thumbColor: Color,
)

data class PlayerSliderStyle(
    val wavelength: Dp = 38.dp,
    val waveHeight: Dp = 8.dp,
    val strokeWidth: Dp = 4.dp,
    val trackStrokeWidth: Dp = 4.dp,
    val gapSize: Dp = 6.dp,
    val thumbRadius: Dp = 8.dp,
    val thumbWidth: Dp = 6.dp,
    val thumbHeight: Dp = 20.dp,
    val sliderHeight: Dp = 48.dp,
    val waveDurationMs: Int = 2300,
)

class WaveGeometryCache(
    private val progressPathCount: Int = 1,
) {
    private var currentWavelength = -1f
    private var currentWaveHeight = -1f
    private var currentAmplitude = -1f
    private var currentThumbWidth = -1f
    private var currentSize: Size = Size.Unspecified
    private var currentProgressFractions: FloatArray? = null
    private var currentIndicatorTrackGapSize = 0f
    private var currentWaveOffset = -1f
    private var currentStrokeWidth = 0f
    private var currentTrackStrokeWidth = 0f
    private var currentStrokeCapWidth = 0f

    private var progressPathScale = 1f

    private val fullProgressPath: Path = Path()
    private val pathMeasure: PathMeasure = PathMeasure()
    val trackPathToDraw: Path = Path()

    var progressPathsToDraw: Array<Path>? = null
        private set

    fun updatePaths(
        size: Size,
        wavelength: Float,
        waveHeight: Float,
        thumbWidth: Float,
        progressFractions: FloatArray,
        amplitude: Float,
        waveOffset: Float,
        gapSize: Float,
        strokeWidth: Float,
        trackStrokeWidth: Float,
    ) {
        if (currentProgressFractions == null) {
            val pairCount = progressPathCount
            currentProgressFractions = FloatArray(pairCount * 2)
            progressPathsToDraw = Array(pairCount) { Path() }
        }
        val pathsUpdated = updateFullPaths(
            size = size,
            wavelength = wavelength,
            waveHeight = waveHeight,
            thumbWidth = thumbWidth,
            gapSize = gapSize,
            strokeWidth = strokeWidth,
            trackStrokeWidth = trackStrokeWidth,
        )
        updateDrawPaths(
            forceUpdate = pathsUpdated,
            progressFractions = progressFractions,
            amplitude = amplitude,
            waveOffset = waveOffset,
        )
    }

    private fun updateFullPaths(
        size: Size,
        wavelength: Float,
        waveHeight: Float,
        thumbWidth: Float,
        gapSize: Float,
        strokeWidth: Float,
        trackStrokeWidth: Float,
    ): Boolean {
        if (
            currentSize == size &&
            currentWavelength == wavelength &&
            currentWaveHeight == waveHeight &&
            currentThumbWidth == thumbWidth &&
            currentStrokeWidth == strokeWidth &&
            currentTrackStrokeWidth == trackStrokeWidth &&
            currentIndicatorTrackGapSize == gapSize &&
            (currentWavelength > 0f) == (wavelength > 0f)
        ) {
            return false
        }

        val height = size.height
        val width = size.width

        currentStrokeCapWidth = max(strokeWidth / 2f, trackStrokeWidth / 2f)

        fullProgressPath.rewind()
        fullProgressPath.moveTo(0f, 0f)

        if (wavelength <= 0f) {
            fullProgressPath.lineTo(width, 0f)
        } else {
            val halfWavelengthPx = wavelength / 2f
            var anchorX = halfWavelengthPx
            val anchorY = 0f
            var controlX = halfWavelengthPx / 2f
            var controlY = waveHeight - strokeWidth

            val widthWithExtraPhase = width + wavelength * 2
            while (anchorX <= widthWithExtraPhase) {
                fullProgressPath.quadraticTo(
                    controlX, controlY, anchorX, anchorY)
                anchorX += halfWavelengthPx
                controlX += halfWavelengthPx
                controlY *= -1f
            }
        }

        fullProgressPath.translate(Offset(x = 0f, y = height / 2f))

        pathMeasure.setPath(path = fullProgressPath, forceClosed = false)

        val fullPathLength = pathMeasure.length
        progressPathScale = fullPathLength / (fullProgressPath.getBounds().width + 0.00000001f)

        currentSize = size
        currentWavelength = wavelength
        currentWaveHeight = waveHeight
        currentThumbWidth = thumbWidth
        currentStrokeWidth = strokeWidth
        currentTrackStrokeWidth = trackStrokeWidth
        currentIndicatorTrackGapSize = gapSize
        return true
    }

    private fun updateDrawPaths(
        forceUpdate: Boolean,
        progressFractions: FloatArray,
        amplitude: Float,
        waveOffset: Float,
    ) {
        require(currentSize != Size.Unspecified) {
            "updateDrawPaths was called before updateFullPaths"
        }
        val paths = progressPathsToDraw
        require(paths != null && paths.size == progressFractions.size / 2) {
            "progress fraction pairs do not match the number of progress paths to draw."
        }
        if (
            !forceUpdate &&
            currentProgressFractions.contentEquals(progressFractions) &&
            currentAmplitude == amplitude &&
            currentWaveOffset == waveOffset
        ) {
            return
        }

        val width = currentSize.width
        val halfHeight = currentSize.height / 2f
        var adjustedTrackGapSize = currentIndicatorTrackGapSize
        var activeIndicatorVisible = false

        val horizontalPadding = max(currentThumbWidth / 2f, currentStrokeCapWidth)
        val activeWidth = width - 2 * horizontalPadding

        var nextEndTrackOffset = width - horizontalPadding
        trackPathToDraw.rewind()
        trackPathToDraw.moveTo(x = nextEndTrackOffset, y = halfHeight)

        for (i in paths.indices) {
            paths[i].rewind()

            val startProgressFraction = progressFractions[i * 2]
            val eventEndProgressFraction = progressFractions[i * 2 + 1]

            val barTail = horizontalPadding + startProgressFraction * activeWidth
            val barHead = horizontalPadding + eventEndProgressFraction * activeWidth

            if (i == 0) {
                adjustedTrackGapSize = currentIndicatorTrackGapSize
                activeIndicatorVisible = true
            }

            val adjustedBarHead = barHead.coerceIn(horizontalPadding, width - horizontalPadding)
            val adjustedBarTail = barTail.coerceIn(horizontalPadding, width - horizontalPadding)

            if (abs(eventEndProgressFraction - startProgressFraction) > 0) {
                val waveShift =
                    if (amplitude != 0f) waveOffset * currentWavelength else 0f

                pathMeasure.getSegment(
                    startDistance = (adjustedBarTail + waveShift) * progressPathScale,
                    stopDistance = (adjustedBarHead + waveShift) * progressPathScale,
                    destination = paths[i],
                )

                paths[i].transform(
                    Matrix().apply {
                        translate(
                            x = if (waveShift > 0f) -waveShift else 0f,
                            y = (1f - amplitude) * halfHeight,
                        )
                        if (amplitude != 1f) {
                            scale(y = amplitude)
                        }
                    }
                )
            }

            val adaptiveTrackSpacing =
                if (activeIndicatorVisible) {
                    adjustedTrackGapSize + currentStrokeCapWidth * 2
                } else {
                    adjustedTrackGapSize
                }

            if (nextEndTrackOffset > adjustedBarHead + adaptiveTrackSpacing) {
                trackPathToDraw.lineTo(
                    x = max(horizontalPadding, adjustedBarHead + adaptiveTrackSpacing),
                    y = halfHeight,
                )
            }

            nextEndTrackOffset = max(horizontalPadding, adjustedBarTail - adaptiveTrackSpacing)
            trackPathToDraw.moveTo(x = nextEndTrackOffset, y = halfHeight)
        }

        if (nextEndTrackOffset > horizontalPadding) {
            trackPathToDraw.lineTo(x = horizontalPadding, y = halfHeight)
        }

        progressFractions.copyInto(currentProgressFractions!!)
        currentAmplitude = amplitude
        currentWaveOffset = waveOffset
    }
}

@Composable
fun ExpressiveSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    colors: PlayerSliderColors,
    style: PlayerSliderStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPlaying: Boolean = true,
    onValueChange: (Float) -> Unit = {},
    onValueChangeFinished: () -> Unit = {},
) {
    val rangeStart = valueRange.start
    val rangeSpan = valueRange.endInclusive - rangeStart

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubbingValue by remember { mutableFloatStateOf(0f) }

    val visualValue = if (isScrubbing) scrubbingValue else value
    val coercedVisual = visualValue.coerceIn(valueRange)
    val fraction = if (rangeSpan == 0f) 0f else (coercedVisual - rangeStart) / rangeSpan

    val gestureAmplitude by animateFloatAsState(
        targetValue = if (isScrubbing || !isPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
    )

    val geometryCache = remember { WaveGeometryCache() }

    val infiniteTransition = rememberInfiniteTransition()
    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = style.waveDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
    )

    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    val currentValueRange by rememberUpdatedState(valueRange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(style.sliderHeight)
            .pointerInput(Unit) {
                awaitEachGesture {
                    if (!currentEnabled) return@awaitEachGesture

                    val down = awaitFirstDown(requireUnconsumed = false)
                    val canvasWidth = size.width.toFloat()
                    if (canvasWidth <= 0f) return@awaitEachGesture

                    val thumbWidthPx = style.thumbWidth.toPx()
                    val strokeWidthPx = style.strokeWidth.toPx()
                    val trackStrokeWidthPx = style.trackStrokeWidth.toPx()
                    val strokeCapWidth = max(strokeWidthPx / 2f, trackStrokeWidthPx / 2f)
                    val horizontalPadding = max(thumbWidthPx / 2f, strokeCapWidth)
                    val activeWidth = canvasWidth - 2 * horizontalPadding

                    var f = if (activeWidth <= 0f) 0f else (
                            (down.position.x - horizontalPadding) / activeWidth).coerceIn(0f, 1f)
                    var targetVal = currentValueRange.start + f * (
                            currentValueRange.endInclusive - currentValueRange.start)

                    isScrubbing = true
                    scrubbingValue = targetVal
                    currentOnValueChange(targetVal)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }

                        if (change == null) {
                            if (event.changes.none { it.pressed }) break
                            continue
                        }

                        if (!change.pressed) break

                        f = if (activeWidth <= 0f) 0f else (
                                (change.position.x - horizontalPadding) / activeWidth).coerceIn(
                            0f, 1f)
                        targetVal = currentValueRange.start + f * (
                                currentValueRange.endInclusive - currentValueRange.start)

                        scrubbingValue = targetVal
                        currentOnValueChange(targetVal)
                        change.consume()
                    }

                    isScrubbing = false
                    currentOnValueChangeFinished()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val wavelengthPx = style.wavelength.toPx()
            val waveHeightPx = style.waveHeight.toPx()
            val strokeWidthPx = style.strokeWidth.toPx()
            val trackStrokeWidthPx = style.trackStrokeWidth.toPx()
            val gapSizePx = style.gapSize.toPx()
            val thumbWidthPx = style.thumbWidth.toPx()
            val thumbHeightPx = style.thumbHeight.toPx()

            val strokeCapWidth = max(strokeWidthPx / 2f, trackStrokeWidthPx / 2f)
            val horizontalPadding = max(thumbWidthPx / 2f, strokeCapWidth)
            val activeWidth = size.width - 2 * horizontalPadding

            geometryCache.updatePaths(
                size = size,
                wavelength = wavelengthPx,
                waveHeight = waveHeightPx,
                thumbWidth = thumbWidthPx,
                progressFractions = floatArrayOf(0f, fraction),
                amplitude = gestureAmplitude,
                waveOffset = waveOffset,
                gapSize = gapSizePx,
                strokeWidth = strokeWidthPx,
                trackStrokeWidth = trackStrokeWidthPx,
            )

            drawPath(
                path = geometryCache.trackPathToDraw,
                color = colors.trackColor,
                style = Stroke(width = trackStrokeWidthPx, cap = StrokeCap.Round),
            )

            geometryCache.progressPathsToDraw?.forEach { path ->
                drawPath(
                    path = path,
                    color = colors.waveColor,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                )
            }

            val centerY = size.height / 2f
            val thumbX = horizontalPadding + activeWidth * fraction

            drawLine(
                color = colors.thumbColor,
                start = Offset(x = thumbX, y = centerY - (thumbHeightPx / 2f)),
                end = Offset(x = thumbX, y = centerY + (thumbHeightPx / 2f)),
                strokeWidth = thumbWidthPx,
                cap = StrokeCap.Round
            )
        }
    }
}