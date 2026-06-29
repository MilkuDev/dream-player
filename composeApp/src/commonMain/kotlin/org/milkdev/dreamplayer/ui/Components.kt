@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.milkdev.dreamplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.app.AppTheme.spacing
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.close
import org.milkdev.dreamplayer.generated.resources.music_note
import org.milkdev.dreamplayer.generated.resources.search
import org.milkdev.dreamplayer.generated.resources.star
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.model.LibraryCategory
import org.milkdev.dreamplayer.model.LibrarySortOrder
import org.milkdev.dreamplayer.playback.Screen
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NavigationDock(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    searchButtonModifier: Modifier = Modifier,
    searchIconModifier: Modifier = Modifier,
) {
    val spacing = spacing
    val dockBackgroundColor = MaterialTheme.colorScheme.secondaryContainer

    LookaheadScope {
        val isLibrarySelected = currentScreen == Screen.Library ||
            currentScreen == Screen.PlaylistDetails ||
            currentScreen == Screen.LibraryCollectionDetails

        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .clip(CircleShape)
                    .background(dockBackgroundColor)
                    .padding(horizontal = spacing.small, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.extraSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockButton(
                    selected = currentScreen == Screen.Home,
                    icon = Res.drawable.star,
                    label = "Главная",
                    contentDescription = "Главная",
                    onClick = { onNavigate(Screen.Home) },
                    modifier = Modifier.animateBounds(this@LookaheadScope),
                )

                DockButton(
                    selected = isLibrarySelected,
                    icon = Res.drawable.music_note,
                    label = "Библиотека",
                    contentDescription = "Библиотека",
                    onClick = { onNavigate(Screen.Library) },
                    modifier = Modifier.animateBounds(this@LookaheadScope),
                )
            }

            Spacer(modifier = Modifier.width(spacing.medium))

            DockButton(
                selected = false,
                icon = Res.drawable.search,
                contentDescription = "Поиск",
                onClick = onSearchClick,
                modifier = searchButtonModifier
                    .animateBounds(this@LookaheadScope)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(dockBackgroundColor),
                iconModifier = searchIconModifier,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.SearchDock(
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerModifier: Modifier = Modifier,
    searchIconModifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val contentColor = MaterialTheme.colorScheme.onPrimary

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = containerModifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .skipToLookaheadSize()
                    .padding(horizontal = spacing.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.small)
            ) {
                IconButton(
                    onClick = onCloseClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.close),
                        contentDescription = "Закрыть поиск",
                        tint = contentColor,
                    )
                }

                val textFieldStyle = AppTheme.typography.snPro.titleMedium.copy(
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Start,
                )

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = textFieldStyle,
                    cursorBrush = SolidColor(contentColor),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isBlank()) {
                                Text(
                                    text = "Введите для поиска",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contentColor,
                                    style = AppTheme.typography.snPro.titleMedium,
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Icon(
                    painter = painterResource(Res.drawable.search),
                    contentDescription = "Поиск",
                    tint = contentColor,
                    modifier = searchIconModifier.padding(
                        spacing.medium).size(24.dp)
                )
            }
        }
    }
}

@Composable
expect fun TrackImage(
    uri: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Album Art",
    fallbackIcon: DrawableResource = Res.drawable.music_note,
    maxDecodeSizePx: Int = 512,
    loadUncached: Boolean = true,
)

@Composable
private fun DockButton(
    selected: Boolean,
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    iconModifier: Modifier = Modifier,
) {
    val spacing = spacing
    val shape = CircleShape
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSecondaryContainer
    val backgroundColor =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.secondaryContainer

    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (label != null && selected) spacing.medium else spacing.default
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = iconModifier.size(24.dp)
        )

        if (label != null) {
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(),
                exit = fadeOut() + shrinkHorizontally { 0 },
            ) {
                Row(
                    Modifier
                        .wrapContentSize(
                            // * Прикрепить контент к левому краю *
                            align = Alignment.CenterStart,
                            unbounded = true
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    transition.AnimatedVisibility(
                        visible = { it == EnterExitState.Visible || it == EnterExitState.PreEnter },
                        // свернуть по горизонтали до нулевого размера
                        exit = shrinkHorizontally { 0 },
                    ) {
                        Spacer(modifier = Modifier.width(spacing.small))
                    }

                    Text(
                        text = label,
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        style = AppTheme.typography.snPro.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LibraryCategoryRow(
    selectedCategory: LibraryCategory,
    onCategorySelected: (LibraryCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        ButtonGroup(
            overflowIndicator = {},
            expandedRatio = 0f,
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier
                .layout { measurable, constraints ->
                    val relaxedConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                    val placeable = measurable.measure(relaxedConstraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                }
        ) {
            LibraryCategory.entries.forEach { category ->
                toggleableItem(
                    checked = selectedCategory == category,
                    onCheckedChange = { checked ->
                        if (checked) onCategorySelected(category)
                    },
                    label = category.label
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : LibrarySortOrder> SortButtonGroupRow(
    selectedSort: T,
    onSortSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    entries: List<T>,
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        ButtonGroup(
            overflowIndicator = {},
            expandedRatio = 0f,
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier
                .layout { measurable, constraints ->
                    val relaxedConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                    val placeable = measurable.measure(relaxedConstraints)
                    layout(placeable.width, placeable.height) {
                        placeable.placeRelative(0, 0)
                    }
                }
        ) {
            entries.forEach { sortOrder ->
                val isSelected = selectedSort == sortOrder
                toggleableItem(
                    checked = isSelected,
                    onCheckedChange = { checked ->
                        if (checked) onSortSelected(sortOrder)
                    },
                    icon = {
                        Icon(
                            painter = painterResource(sortOrder.icon),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = sortOrder.label
                )
            }
        }
    }
}

val M3ESquareShape: Shape
    @Composable get() = MaterialShapes.Square.toShape()
val M3EClover4Leaf: Shape
    @Composable get() = MaterialShapes.Clover4Leaf.toShape()
val M3ESunnyShape: Shape
    @Composable get() = MaterialShapes.Sunny.toShape()
val M3ECookie12SidedShape: Shape
    @Composable get() = MaterialShapes.Cookie12Sided.toShape()
val M3ECookie9SidedShape: Shape
    @Composable get() = MaterialShapes.Cookie9Sided.toShape()
val M3ECookie6SidedShape: Shape
    @Composable get() = MaterialShapes.Cookie6Sided.toShape()
val M3EFlowerShape: Shape
    @Composable get() = MaterialShapes.Flower.toShape()
val M3EPuffyShape: Shape
    @Composable get() = MaterialShapes.Puffy.toShape()
val M3EHeartShape: Shape
    @Composable get() = MaterialShapes.Heart.toShape()
val M3EPixelTriangleShape: Shape
    @Composable get() = MaterialShapes.PixelTriangle.toShape()

@Composable
fun RecommendationCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkUri: String? = null,
    fallbackIcon: DrawableResource = Res.drawable.music_note,
    imageShape: Shape = RoundedCornerShape(24.dp),
    cardWidth: Dp = 140.dp,
    loadUncached: Boolean = true,
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(cardWidth)
                .clip(imageShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            TrackImage(
                uri = artworkUri,
                modifier = Modifier.fillMaxSize(),
                fallbackIcon = fallbackIcon,
                maxDecodeSizePx = 320,
                loadUncached = loadUncached,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = AppTheme.typography.snPro.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = AppTheme.typography.snPro.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun ContentSection(
    title: String,
    tracks: List<LibraryTrack>,
    onTrackClick: (LibraryTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tracks.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = title,
            style = AppTheme.typography.snPro.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(tracks) { track ->
                RecommendationCard(
                    title = track.title,
                    subtitle = track.artistName,
                    artworkUri = track.albumArtUri,
                    onClick = { onTrackClick(track) }
                )
            }
        }
    }
}

fun Dp.nestedShape(padding: Dp): RoundedCornerShape {
    return RoundedCornerShape((this - padding).coerceAtLeast(0.dp))
}
@Composable
fun Modifier.shapeClickableWithFeedback(
    shape: Shape,
    onClickDelay: Duration = ClickDelays.Shape,
    borderDuration: Duration = BorderDelays.DefaultBorder,
    fadeOutDuration: Duration = BorderDelays.ExpressiveFade,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    var isVisualPressed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val showBorder = isPressed || isVisualPressed
    val expressiveEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    val animatedAlpha by animateFloatAsState(
        targetValue = if (showBorder) 1f else 0f,
        animationSpec = if (showBorder) {
            tween(durationMillis = 60, easing = LinearEasing)
        } else {
            tween(durationMillis = fadeOutDuration.inWholeMilliseconds.toInt(), easing = expressiveEasing)
        },
        label = "ShapeBorderAlpha"
    )

    return this
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = {
                scope.launch {
                    isVisualPressed = true

                    launch {
                        delay(onClickDelay)
                        onClick()
                    }

                    val visualDuration = if (onClickDelay < borderDuration) borderDuration else onClickDelay
                    delay(visualDuration)

                    isVisualPressed = false
                }
            }
        )
        .then(
            if (animatedAlpha > 0f) {
                Modifier.border(3.dp, Color(0xEEFFFFFF).copy(alpha = animatedAlpha), shape)
            } else {
                Modifier
            }
        )
}

object ClickDelays {
    val Shape = 10.milliseconds
    val Shuffle = 0.milliseconds
    val Play = 0.milliseconds
}

object BorderDelays {
    val DefaultBorder = 150.milliseconds
    val ExtendedBorder = 300.milliseconds
    val ExpressiveFade = 300.milliseconds
    val ExpressiveFastFade = 150.milliseconds
}