package org.milkdev.dreamplayer.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.pause
import org.milkdev.dreamplayer.generated.resources.play_arrow
import org.milkdev.dreamplayer.generated.resources.skip_next
import org.milkdev.dreamplayer.generated.resources.skip_previous
import org.milkdev.dreamplayer.library.LibraryTrack
import org.milkdev.dreamplayer.model.PlayerViewModel
import org.milkdev.dreamplayer.playback.PlayerUiState
import org.milkdev.dreamplayer.playback.Screen
import org.milkdev.dreamplayer.ui.*

private val playerViewModelInstance = PlayerViewModel()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun App(
    playerViewModel: PlayerViewModel = playerViewModelInstance,
    isPermissionGranted: Boolean = true
) {
    val state by playerViewModel.state.collectAsState()

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            playerViewModel.loadLibrary()
        }
    }

    val hazeState = rememberHazeState()

    AppTheme(darkTheme = state.isForceNightMode || androidx.compose.foundation.isSystemInDarkTheme()) {
        PlatformBackHandler(enabled = state.canNavigateBack) {
            playerViewModel.navigateBack()
        }

        val spacing = AppTheme.spacing
        val playerSidePadding = 16.dp
        val playerTopPadding = 16.dp

        val playerBarRadius = 32.dp
        val searchBarRadius = 24.dp
        val defaultDockRadius = 20.dp

        val activeTopRadius = when {
            state.currentTrack != null -> playerBarRadius
            state.currentScreen == Screen.Search -> searchBarRadius
            else -> defaultDockRadius
        }

        val animatedBlurContainerRadius by animateDpAsState(
            targetValue = activeTopRadius + playerSidePadding,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
            bottomBar = {
                if (state.currentScreen != Screen.Settings && state.currentScreen != Screen.AiDebugSettings) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .disableClicks()
                            .clip(RoundedCornerShape(
                                topStart = animatedBlurContainerRadius,
                                topEnd = animatedBlurContainerRadius
                            ))
                            .then(
                                if (state.isBlurEnabled) {
                                    Modifier.hazeEffect(hazeState) {
                                        blurRadius = 16.dp
                                    }
                                } else Modifier.background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(playerTopPadding))

                        if (state.currentTrack != null) {
                            PlayerBar(
                                currentTrack = state.currentTrack,
                                isPlaying = state.isPlaying,
                                onPreviousClick = { playerViewModel.playPrevious() },
                                onNextClick = { playerViewModel.playNext() },
                                onPlayPauseClick = {
                                    if (state.isPlaying) playerViewModel.pause() else playerViewModel.resume()
                                },
                                onClick = { playerViewModel.openPlayer() },
                                modifier = Modifier.padding(horizontal = playerSidePadding),
                                barRadius = playerBarRadius
                            )
                        }

                        Spacer(modifier = Modifier.height(spacing.medium))

                        BottomDock(
                            state = state,
                            onNavigate = playerViewModel::navigateTo,
                            onOpenSearch = playerViewModel::openLibrarySearch,
                            onCloseSearch = { playerViewModel.navigateBack() },
                            onSearchQueryChange = playerViewModel::updateLibrarySearchQuery,
                        )
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val saveableStateHolder = rememberSaveableStateHolder()
                AnimatedContent(
                    targetState = state.currentScreen,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                    }
                ) { targetScreen ->
                    saveableStateHolder.SaveableStateProvider(targetScreen) {
                        when (targetScreen) {
                            Screen.Home -> HomeScreen(
                                state = state,
                                onShuffleDailyPlaylistClick = playerViewModel::shuffleDailyPlaylist,
                                onOpenDailyPlaylistClick = playerViewModel::openDailyPlaylist,
                                contentPadding = paddingValues,
                                onSettingsClick = { playerViewModel.navigateTo(Screen.Settings) },
                                onTrackClick = playerViewModel::playFromVisibleTracks
                            )
                            Screen.Library -> LibraryScreen(
                                state = state,
                                onIntent = playerViewModel::onLibraryIntent,
                                contentPadding = paddingValues,
                            )
                            Screen.PlaylistDetails -> PlaylistDetailsScreen(
                                state = state,
                                onBackClick = { playerViewModel.navigateBack() },
                                onTrackClick = playerViewModel::playFromSelectedPlaylist,
                                onSaveTracks = playerViewModel::savePlaylistTracks,
                                onLoadNextPickerTracks = { playerViewModel.loadPlaylistPickerPage() },
                                contentPadding = paddingValues,
                            )
                            Screen.LibraryCollectionDetails -> LibraryCollectionDetailsScreen(
                                state = state,
                                onBackClick = { playerViewModel.navigateBack() },
                                onTrackClick = playerViewModel::playFromSelectedLibraryCollection,
                                onAlbumClick = playerViewModel::openAlbumDetails,
                                contentPadding = paddingValues,
                            )
                            Screen.Settings -> SettingsScreen(
                                state = state,
                                onBackClick = { playerViewModel.navigateBack() },
                                onBlurToggle = { playerViewModel.setBlurEnabled(it) },
                                onNightModeToggle = { playerViewModel.setForceNightMode(it) },
                                onDailyPlaylistModeChange = { playerViewModel.setDailyPlaylistGenerationMode(it) },
                                onAiPlaylistProviderChange = { playerViewModel.setAiPlaylistProvider(it) },
                                onAiPlaylistModelChange = { playerViewModel.setAiPlaylistModel(it) },
                                onAiPlaylistPromptPresetChange = { playerViewModel.setAiPlaylistPromptPreset(it) },
                                onAiPlaylistCustomSystemPromptChange = { playerViewModel.setAiPlaylistCustomSystemPrompt(it) },
                                onAiPlaylistApiKeySave = { playerViewModel.saveAiPlaylistApiKey(it) },
                                onAiPlaylistApiKeyClear = { playerViewModel.clearAiPlaylistApiKey() },
                                onOpenAiDebugSettings = { playerViewModel.navigateTo(Screen.AiDebugSettings) },
                                onLastFmApiKeySave = { playerViewModel.saveLastFmApiKey(it) },
                                onLastFmApiKeyClear = { playerViewModel.clearLastFmApiKey() },
                                onLastFmApiTest = { playerViewModel.testLastFmApi() },
                                onLastFmMetadataSync = { playerViewModel.startLastFmMetadataSync() },
                                onMusicBrainzCoverSync = { playerViewModel.startMusicBrainzCoverSync() },
                                contentPadding = paddingValues
                            )
                            Screen.AiDebugSettings -> AiDebugSettingsScreen(
                                state = state,
                                onBackClick = { playerViewModel.navigateBack() },
                                onModeChange = { playerViewModel.setDailyPlaylistGenerationMode(it) },
                                onProviderChange = { playerViewModel.setAiPlaylistProvider(it) },
                                onModelChange = { playerViewModel.setAiPlaylistModel(it) },
                                onPromptPresetChange = { playerViewModel.setAiPlaylistPromptPreset(it) },
                                onCustomPromptChange = { playerViewModel.setAiPlaylistCustomSystemPrompt(it) },
                                onApiKeySave = { playerViewModel.saveAiPlaylistApiKey(it) },
                                onApiKeyClear = { playerViewModel.clearAiPlaylistApiKey() },
                                onAllApiKeysClear = { playerViewModel.clearAllAiPlaylistApiKeys() },
                                onApiTest = { playerViewModel.testAiPlaylistApi() },
                                onResponseTest = { playerViewModel.testAiPlaylistModelResponse() },
                                onForceGenerateDailyPlaylist = { playerViewModel.forceGenerateDailyPlaylist() },
                                onPromptSend = { playerViewModel.sendAiPrompt(it) },
                                contentPadding = paddingValues
                            )
                            Screen.Search -> LibrarySearchScreen(
                                state = state,
                                onTrackClick = playerViewModel::playFromVisibleTracks,
                                onBackClick = { playerViewModel.navigateBack() },
                                onLoadNextSearch = { playerViewModel.loadNextSearchPage() },
                                contentPadding = paddingValues
                            )
                            Screen.Player, Screen.Queue -> {}
                        }
                    }
                }
            }
        }
            PlayerOverlayHost(
                state = state,
                onNavigateBack = { playerViewModel.navigateBack() },
                onOpenQueueSheet = playerViewModel::openQueueSheet,
                onPreviousClick = playerViewModel::playPrevious,
                onNextClick = playerViewModel::playNext,
                onPlayPauseClick = {
                    if (state.isPlaying) playerViewModel.pause() else playerViewModel.resume()
                },
                onShuffleClick = playerViewModel::toggleShuffle,
                onRepeatClick = playerViewModel::toggleRepeat,
                onFavoriteClick = playerViewModel::favorite,
                onSeek = playerViewModel::seekTo,
                onQueueTrackClick = playerViewModel::playQueueItem,
                onMoveTrack = playerViewModel::moveTrack,
                onClearQueueClick = playerViewModel::clearQueue,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun BottomDock(
    state: PlayerUiState,
    onNavigate: (Screen) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = AppTheme.spacing.medium)
    ) {
        val isSearchDockVisible = state.currentScreen == Screen.Search
        val searchBoundsState = rememberSharedContentState(key = "bottom_dock_search_bounds")
        val searchIconState = rememberSharedContentState(key = "bottom_dock_search_icon")

        AnimatedContent(
            targetState = isSearchDockVisible,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 40)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 160))
            },
            label = "BottomDock",
        ) { isSearchActive ->
            if (isSearchActive) {
                this@SharedTransitionLayout.SearchDock(
                    query = state.librarySearch.query,
                    onQueryChange = onSearchQueryChange,
                    onCloseClick = onCloseSearch,
                    containerModifier = Modifier.sharedBounds(
                        sharedContentState = searchBoundsState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                        clipInOverlayDuringTransition = OverlayClip(CircleShape)
                    ),
                    searchIconModifier = Modifier.sharedElement(
                        sharedContentState = searchIconState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                    ),
                )
            } else {
                NavigationDock(
                    currentScreen = state.currentScreen,
                    onNavigate = onNavigate,
                    onSearchClick = onOpenSearch,
                    searchButtonModifier = Modifier.sharedBounds(
                        sharedContentState = searchBoundsState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                        clipInOverlayDuringTransition = OverlayClip(CircleShape)
                    ),
                    searchIconModifier = Modifier.sharedElement(
                        sharedContentState = searchIconState,
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = DockBoundsTransform,
                    ),
                )
            }
        }
    }
}

private val DockBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = 420)
}

@Composable
fun PlayerBar(
    currentTrack: LibraryTrack?,
    isPlaying: Boolean,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    barRadius: Dp = 32.dp
) {
    val spacing = AppTheme.spacing
    val barShape = RoundedCornerShape(barRadius)

    val albumArtPadding = spacing.large
    val albumArtShape = barRadius.nestedShape(padding = albumArtPadding)

    val buttonPadding = spacing.small
    // val playPauseShape = barRadius.nestedShape(padding = buttonPadding)

    Surface(
        onClick = onClick,
        shape = barShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        tonalElevation = 6.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = albumArtPadding,
                    top = buttonPadding,
                    end = buttonPadding,
                    bottom = buttonPadding
                ),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(albumArtShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
            ) {
                TrackImage(
                    uri = currentTrack?.albumArtUri,
                    modifier = Modifier.fillMaxSize(),
                    maxDecodeSizePx = 160,
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = currentTrack?.title ?: "Название трека",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTheme.typography.snPro.titleMedium
                )
                Text(
                    text = currentTrack?.artistName ?: "Исполнитель",
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = AppTheme.typography.snPro.bodyMedium
                )
            }

            PlayerIconButton(
                icon = Res.drawable.skip_previous,
                contentDescription = "Предыдущий трек",
                onClick = onPreviousClick
            )

            PlayerIconButton(
                icon = Res.drawable.skip_next,
                contentDescription = "Следующий трек",
                onClick = onNextClick
            )

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(M3ECookie9SidedShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .shapeClickableWithFeedback(
                        shape = M3ECookie9SidedShape,
                        onClickDelay = ClickDelays.Play,
                        borderDuration = BorderDelays.ExtendedBorder,
                        fadeOutDuration = BorderDelays.ExpressiveFastFade,
                        onClick = onPlayPauseClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) Res.drawable.pause else Res.drawable.play_arrow),
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun PlayerIconButton(
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp)
        )
    }
}
