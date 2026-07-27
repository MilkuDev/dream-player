package org.milkdev.dreamplayer.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import org.milkdev.dreamplayer.model.PresentationEvictionToken
import org.milkdev.dreamplayer.navigation.NavigationPlan
import org.milkdev.dreamplayer.navigation.NavigationTransaction
import org.milkdev.dreamplayer.navigation.toMainTabOrNull

internal sealed interface RetainedSceneKey {
    data object RootCarousel : RetainedSceneKey

    data class Entry(
        val entryId: Long,
    ) : RetainedSceneKey
}

private data class RetainedSceneRecord(
    val key: RetainedSceneKey,
    val scene: ContentSceneSnapshot,
) {
    val saveableStateKey: Any
        get() = when (key) {
            RetainedSceneKey.RootCarousel -> RootCarouselSaveableStateKey
            is RetainedSceneKey.Entry -> key.entryId
        }
}

internal data class RetainedOrdinaryTransition(
    val fromKey: RetainedSceneKey,
    val toKey: RetainedSceneKey,
    val motionKind: NavigationMotionKind,
    val removedAtRevision: Long,
    val progress: Float = 0f,
)

@Stable
internal class RetainedContentStackState(
    initialNavigationRevision: Long = 0L,
) {
    var presentationState: ContentNavigationPresentationState by mutableStateOf(
        ContentNavigationPresentationState.Idle,
    )
        private set

    var ordinaryTransition: RetainedOrdinaryTransition? by mutableStateOf(null)
        private set

    var visualBackProgress: Float by mutableStateOf(0f)
        private set

    var authorityEpoch: Long by mutableLongStateOf(1L)
        private set

    var settledNavigationRevision: Long by mutableLongStateOf(initialNavigationRevision)
        private set

    var hasQueuedTimeDrivenBack: Boolean by mutableStateOf(false)
        private set

    private var nextSessionId = 1L
    private var awaitingCommittedSessionId: Long? = null
    private var deferredBackGestureInProgress = false

    val backSession: ContentBackSession?
        get() = presentationState.backSession

    val isTransitionRunning: Boolean
        get() = ordinaryTransition != null ||
            presentationState !is ContentNavigationPresentationState.Idle

    fun canRequestBack(): Boolean =
        presentationState is ContentNavigationPresentationState.Idle &&
            ordinaryTransition == null

    fun isSettledAt(navigationRevision: Long): Boolean =
        settledNavigationRevision == navigationRevision && !isTransitionRunning

    fun beginDeferredBackGesture() {
        deferredBackGestureInProgress = true
    }

    fun cancelDeferredBackGesture() {
        deferredBackGestureInProgress = false
    }

    fun commitDeferredBackGesture() {
        if (deferredBackGestureInProgress) {
            hasQueuedTimeDrivenBack = true
        }
        deferredBackGestureInProgress = false
    }

    fun takeQueuedTimeDrivenBackIf(ready: Boolean): Boolean {
        if (!ready || !hasQueuedTimeDrivenBack) return false
        hasQueuedTimeDrivenBack = false
        return true
    }

    fun startPredictiveBack(
        backPlan: NavigationPlan,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ): ContentBackSession? {
        if (
            !canRequestBack() ||
            backPlan.expectedTopEntryId != origin.currentEntry.entryId ||
            backPlan.targetState.currentContentEntry.entryId != preview.currentEntry.entryId
        ) {
            return null
        }
        val session = newBackSession(
            backPlan = backPlan,
            origin = origin,
            preview = preview,
            mode = ContentBackMode.Predictive,
            source = ContentBackSource.Platform,
        )
        presentationState = ContentNavigationPresentationState.Tracking(session)
        visualBackProgress = 0f
        revokeAuthority()
        return session
    }

    fun startTimeDrivenBack(
        backPlan: NavigationPlan,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
    ): ContentBackSession? {
        if (
            !canRequestBack() ||
            backPlan.expectedTopEntryId != origin.currentEntry.entryId ||
            backPlan.targetState.currentContentEntry.entryId != preview.currentEntry.entryId
        ) {
            return null
        }
        val session = newBackSession(
            backPlan = backPlan,
            origin = origin,
            preview = preview,
            mode = ContentBackMode.TimeDriven,
            source = ContentBackSource.Ui,
        )
        presentationState = ContentNavigationPresentationState.Committing(session)
        visualBackProgress = 0f
        revokeAuthority()
        return session
    }

    fun progressPredictiveBack(
        event: PlatformBackEvent,
        currentTopEntryId: Long,
        currentRevision: Long,
    ) {
        val tracking =
            presentationState as? ContentNavigationPresentationState.Tracking ?: return
        val session = tracking.session
        if (
            session.originTopEntryId != currentTopEntryId ||
            session.originRevision != currentRevision
        ) {
            return
        }
        val progress = event.progress.coerceIn(0f, 1f)
        val velocity = predictiveBackProgressVelocity(
            previousProgress = session.progress,
            previousFrameTimeMillis = session.lastProgressFrameTimeMillis,
            progress = progress,
            frameTimeMillis = event.frameTimeMillis,
        ) ?: session.progressVelocity
        val updated = session.copy(
            progress = progress,
            progressEventCount = session.progressEventCount + 1,
            maxProgress = maxOf(session.maxProgress, progress),
            swipeEdge = event.swipeEdge,
            progressVelocity = velocity,
            lastProgressFrameTimeMillis = event.frameTimeMillis.takeIf { it > 0L }
                ?: session.lastProgressFrameTimeMillis,
        )
        presentationState = ContentNavigationPresentationState.Tracking(updated)
        visualBackProgress = progress
    }

    fun cancelPredictiveBack(): ContentBackSession? {
        val tracking =
            presentationState as? ContentNavigationPresentationState.Tracking ?: return null
        presentationState = ContentNavigationPresentationState.Cancelling(tracking.session)
        return tracking.session
    }

    fun commitPredictiveBack(
        hadProgress: Boolean,
    ): ContentBackSession? {
        val tracking =
            presentationState as? ContentNavigationPresentationState.Tracking ?: return null
        val session = tracking.session.copy(
            mode = if (hadProgress) ContentBackMode.Predictive else ContentBackMode.TimeDriven,
        )
        presentationState = ContentNavigationPresentationState.Committing(session)
        return session
    }

    fun updateSettlingProgress(
        sessionId: Long,
        progress: Float,
    ) {
        val session = presentationState.backSession ?: return
        if (session.sessionId != sessionId) return
        val updatedSession = session.copy(progress = progress.coerceIn(0f, 1f))
        presentationState = when (val state = presentationState) {
            is ContentNavigationPresentationState.Tracking -> state.copy(session = updatedSession)
            is ContentNavigationPresentationState.Cancelling -> state.copy(session = updatedSession)
            is ContentNavigationPresentationState.Committing -> state.copy(session = updatedSession)
            ContentNavigationPresentationState.Idle,
            is ContentNavigationPresentationState.Animating -> state
        }
        visualBackProgress = progress.coerceIn(0f, 1f)
    }

    fun finishCancellation(sessionId: Long) {
        if (presentationState.backSession?.sessionId != sessionId) return
        presentationState = ContentNavigationPresentationState.Idle
        visualBackProgress = 0f
        grantNewAuthority()
    }

    fun markCommitAwaitingNavigation(sessionId: Long) {
        if (presentationState.backSession?.sessionId != sessionId) return
        awaitingCommittedSessionId = sessionId
    }

    fun isAwaitingCommittedNavigation(sessionId: Long): Boolean =
        awaitingCommittedSessionId == sessionId

    fun finishCommittedNavigation(
        sessionId: Long,
        navigationRevision: Long = settledNavigationRevision,
    ) {
        if (awaitingCommittedSessionId != sessionId) return
        awaitingCommittedSessionId = null
        presentationState = ContentNavigationPresentationState.Idle
        visualBackProgress = 0f
        settledNavigationRevision = navigationRevision
        grantNewAuthority()
    }

    fun recoverStaleSession(sessionId: Long) {
        if (presentationState.backSession?.sessionId != sessionId) return
        awaitingCommittedSessionId = null
        presentationState = ContentNavigationPresentationState.Idle
        visualBackProgress = 0f
        grantNewAuthority()
    }

    fun startOrdinaryTransition(transition: RetainedOrdinaryTransition) {
        ordinaryTransition = transition
        revokeAuthority()
    }

    fun updateOrdinaryProgress(progress: Float) {
        ordinaryTransition = ordinaryTransition?.copy(
            progress = progress.coerceIn(0f, 1f),
        )
    }

    fun finishOrdinaryTransition(
        navigationRevision: Long = settledNavigationRevision,
    ) {
        ordinaryTransition = null
        settledNavigationRevision = navigationRevision
        grantNewAuthority()
    }

    fun settleNavigationRevision(navigationRevision: Long) {
        if (settledNavigationRevision == navigationRevision) return
        settledNavigationRevision = navigationRevision
        grantNewAuthority()
    }

    private fun newBackSession(
        backPlan: NavigationPlan,
        origin: ContentSceneSnapshot,
        preview: ContentSceneSnapshot,
        mode: ContentBackMode,
        source: ContentBackSource,
    ): ContentBackSession = ContentBackSession(
        sessionId = nextSessionId++,
        backPlan = backPlan,
        originFrame = ContentTransitionFrame(origin),
        previewFrame = ContentTransitionFrame(
            scene = preview,
            motionContext = backPlan.toMotionContext(origin, preview),
        ),
        mode = mode,
        source = source,
        motionStyle = resolvePredictiveBackMotionStyle(
            origin = origin,
            preview = preview,
            operation = backPlan.operation,
        ),
    )

    private fun revokeAuthority() {
        authorityEpoch += 1L
    }

    private fun grantNewAuthority() {
        authorityEpoch += 1L
    }
}

@Composable
internal fun rememberRetainedContentStackState(
    initialNavigationRevision: Long,
): RetainedContentStackState =
    remember { RetainedContentStackState(initialNavigationRevision) }

@Composable
internal fun RetainedContentStackHost(
    authoritativeScenes: List<ContentSceneSnapshot>,
    navigationRevision: Long,
    navigationTransaction: NavigationTransaction?,
    state: RetainedContentStackState,
    foregroundPresentation: ForegroundPresentation,
    presentationOwnerEpoch: Long,
    modifier: Modifier = Modifier,
    onCommitBack: (ContentBackSession) -> Boolean,
    onSceneEvicted: (PresentationEvictionToken, Long) -> Unit,
    content: @Composable (
        scene: ContentSceneSnapshot,
        policy: SceneExecutionPolicy,
        backSession: ContentBackSession?,
    ) -> Unit,
) {
    val initialRecords = remember {
        authoritativeScenes.mapTo(mutableStateListOf()) { scene ->
            RetainedSceneRecord(scene.retainedKey(), scene)
        }
    }
    val records = initialRecords
    val saveableStateHolder = rememberSaveableStateHolder()
    val latestCommitBack by rememberUpdatedState(onCommitBack)
    val latestOnSceneEvicted by rememberUpdatedState(onSceneEvicted)
    var observedRevision by remember { mutableLongStateOf(navigationRevision) }

    suspend fun evictRecords(
        recordsToEvict: List<RetainedSceneRecord>,
        removedAtRevision: Long,
    ) {
        if (recordsToEvict.isEmpty()) return
        records.removeAll(recordsToEvict.toSet())
        withFrameNanos { }
        recordsToEvict.forEach { record ->
            if (record.key != RetainedSceneKey.RootCarousel) {
                saveableStateHolder.removeState(record.saveableStateKey)
                latestOnSceneEvicted(
                    PresentationEvictionToken(
                        entryId = record.scene.currentEntry.entryId,
                        removedAtRevision = removedAtRevision,
                    ),
                    presentationOwnerEpoch,
                )
            }
        }
    }

    LaunchedEffect(navigationRevision, authoritativeScenes) {
        val previousRecordsByKey = records.associateBy { record -> record.key }
        val authoritativeByKey = authoritativeScenes.associateBy { it.retainedKey() }
        authoritativeScenes.forEach { scene ->
            val sceneKey = scene.retainedKey()
            val existingIndex = records.indexOfFirst { it.key == sceneKey }
            val record = RetainedSceneRecord(sceneKey, scene)
            if (existingIndex >= 0) {
                records[existingIndex] = record
            } else {
                records += record
            }
        }

        if (navigationRevision == observedRevision) return@LaunchedEffect
        observedRevision = navigationRevision

        val committing =
            state.presentationState as? ContentNavigationPresentationState.Committing
        if (
            committing != null &&
            state.isAwaitingCommittedNavigation(committing.session.sessionId)
        ) {
            val removed = records.filter { it.key !in authoritativeByKey }
            evictRecords(removed, navigationRevision)
            state.finishCommittedNavigation(
                sessionId = committing.session.sessionId,
                navigationRevision = navigationRevision,
            )
            return@LaunchedEffect
        }

        state.backSession
            ?.takeIf { session -> session.originRevision != navigationRevision }
            ?.let { staleSession ->
                state.recoverStaleSession(staleSession.sessionId)
            }

        val transaction = navigationTransaction
        if (transaction == null || !transaction.affectsContent) {
            evictRecords(
                records.filter { it.key !in authoritativeByKey },
                navigationRevision,
            )
            state.settleNavigationRevision(navigationRevision)
            return@LaunchedEffect
        }

        val fromKey = transaction.fromContentEntry.retainedKey()
        val toKey = transaction.toContentEntry.retainedKey()
        val initialScene = previousRecordsByKey[fromKey]?.scene
            ?: records.firstOrNull { it.key == fromKey }?.scene
        val targetScene = records.firstOrNull { it.key == toKey }?.scene
        if (initialScene == null || targetScene == null) {
            evictRecords(
                records.filter { it.key !in authoritativeByKey },
                navigationRevision,
            )
            state.settleNavigationRevision(navigationRevision)
            return@LaunchedEffect
        }

        val transition = RetainedOrdinaryTransition(
            fromKey = fromKey,
            toKey = toKey,
            motionKind = resolveNavigationMotion(
                initial = initialScene,
                target = targetScene,
                transaction = transaction,
            ),
            removedAtRevision = navigationRevision,
        )
        val hiddenRemoved = records.filter { record ->
            record.key !in authoritativeByKey && record.key != fromKey
        }
        state.startOrdinaryTransition(transition)
        evictRecords(hiddenRemoved, navigationRevision)
        val progress = Animatable(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = transition.motionKind.retainedDurationMillis(),
                easing = RetainedSceneTransitionEasing,
            ),
        ) {
            state.updateOrdinaryProgress(value)
        }
        withFrameNanos { }
        evictRecords(
            records.filter { it.key !in authoritativeByKey },
            navigationRevision,
        )
        state.finishOrdinaryTransition(navigationRevision)
    }

    val backPhase = state.presentationState
    val backSession = state.backSession
    LaunchedEffect(
        backSession?.sessionId,
        backPhase::class,
        state.isAwaitingCommittedNavigation(backSession?.sessionId ?: -1L),
    ) {
        val session = backSession ?: return@LaunchedEffect
        when (backPhase) {
            is ContentNavigationPresentationState.Cancelling -> {
                val progress = Animatable(state.visualBackProgress)
                progress.updateBounds(0f, 1f)
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = retainedBackSpring(),
                    initialVelocity = session.progressVelocity,
                ) {
                    state.updateSettlingProgress(session.sessionId, value)
                }
                withFrameNanos { }
                state.finishCancellation(session.sessionId)
            }

            is ContentNavigationPresentationState.Committing -> {
                if (state.isAwaitingCommittedNavigation(session.sessionId)) {
                    return@LaunchedEffect
                }
                val progress = Animatable(state.visualBackProgress)
                progress.updateBounds(0f, 1f)
                if (
                    session.motionStyle == PredictiveBackMotionStyle.MainTabCarousel ||
                    session.mode == ContentBackMode.TimeDriven
                ) {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = if (
                                session.motionStyle ==
                                PredictiveBackMotionStyle.MainTabCarousel
                            ) {
                                mainTabCarouselSettleDurationMillis(progress.value)
                            } else {
                                RetainedTimeDrivenBackDurationMillis
                            },
                            easing = MotionEnterEasing,
                        ),
                    ) {
                        state.updateSettlingProgress(session.sessionId, value)
                    }
                } else {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = retainedBackSpring(),
                        initialVelocity = session.progressVelocity,
                    ) {
                        state.updateSettlingProgress(session.sessionId, value)
                    }
                }
                withFrameNanos { }
                if (latestCommitBack(session)) {
                    state.markCommitAwaitingNavigation(session.sessionId)
                } else {
                    state.recoverStaleSession(session.sessionId)
                }
            }

            ContentNavigationPresentationState.Idle,
            is ContentNavigationPresentationState.Animating,
            is ContentNavigationPresentationState.Tracking -> Unit
        }
    }

    val authoritativeTopKey = authoritativeScenes.last().retainedKey()
    check(records.map { record -> record.key }.distinct().size == records.size) {
        "Retained content host must own exactly one composition per scene key"
    }
    Layout(
        modifier = modifier,
        content = {
            records.forEach { record ->
                key(record.key) {
                    saveableStateHolder.SaveableStateProvider(record.saveableStateKey) {
                        val role = record.resolveRole(
                            state = state,
                            authoritativeTopKey = authoritativeTopKey,
                        )
                        val policy = resolveSceneExecutionPolicy(
                            role = role,
                            foregroundPresentation = foregroundPresentation,
                            contentTransitionSettled =
                                state.isSettledAt(navigationRevision),
                            isAuthoritativeContentEntry =
                                record.key == authoritativeTopKey,
                            authorityEpoch = state.authorityEpoch,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .retainedSceneTransform(
                                    record = record,
                                    role = role,
                                    state = state,
                                ),
                        ) {
                            content(
                                record.scene,
                                policy,
                                state.backSession,
                            )
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable -> measurable.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(0, 0, zIndex = index.toFloat())
            }
        }
    }
}

private fun RetainedSceneRecord.resolveRole(
    state: RetainedContentStackState,
    authoritativeTopKey: RetainedSceneKey,
): ScenePresentationRole {
    val session = state.backSession
    if (session != null) {
        val originKey = session.origin.currentEntry.retainedKey()
        val previewKey = session.preview.currentEntry.retainedKey()
        if (key == originKey) return ScenePresentationRole.Origin
        if (key == previewKey) return ScenePresentationRole.Preview
    }
    state.ordinaryTransition?.let { transition ->
        if (key == transition.fromKey && transition.fromKey != transition.toKey) {
            return ScenePresentationRole.Exiting
        }
        if (key == transition.toKey) return ScenePresentationRole.Entering
    }
    return if (key == authoritativeTopKey && !state.isTransitionRunning) {
        ScenePresentationRole.Active
    } else {
        ScenePresentationRole.Retained
    }
}

private fun Modifier.retainedSceneTransform(
    record: RetainedSceneRecord,
    role: ScenePresentationRole,
    state: RetainedContentStackState,
): Modifier {
    val session = state.backSession
    if (
        session != null &&
        session.motionStyle == PredictiveBackMotionStyle.Stack &&
        session.mode == ContentBackMode.Predictive &&
        record.key == session.origin.currentEntry.retainedKey()
    ) {
        return predictiveStackSceneTransform(
            progress = state.visualBackProgress,
            swipeEdge = session.swipeEdge,
        )
    }

    val backMotionKind = if (
        session != null &&
        session.motionStyle == PredictiveBackMotionStyle.Stack &&
        session.mode == ContentBackMode.TimeDriven
    ) {
        NavigationMotionKind.Backward
    } else {
        null
    }
    val transition = state.ordinaryTransition
    val motionKind = backMotionKind ?: transition?.motionKind ?: return this
    val progress = if (backMotionKind != null) {
        state.visualBackProgress
    } else {
        transition?.progress ?: 0f
    }
    val effectiveRole = if (backMotionKind != null && session != null) {
        when (record.key) {
            session.origin.currentEntry.retainedKey() -> ScenePresentationRole.Exiting
            session.preview.currentEntry.retainedKey() -> ScenePresentationRole.Entering
            else -> role
        }
    } else {
        role
    }
    if (
        effectiveRole != ScenePresentationRole.Entering &&
        effectiveRole != ScenePresentationRole.Exiting
    ) {
        return this
    }
    return graphicsLayer {
        val transform = retainedSceneVisualTransform(
            motionKind = motionKind,
            role = effectiveRole,
            progress = progress,
        )
        translationX = size.width * transform.translationXFraction
        alpha = transform.alpha
        scaleX = transform.scale
        scaleY = transform.scale
    }
}

internal data class RetainedSceneVisualTransform(
    val translationXFraction: Float = 0f,
    val alpha: Float = 1f,
    val scale: Float = 1f,
)

internal fun retainedSceneVisualTransform(
    motionKind: NavigationMotionKind,
    role: ScenePresentationRole,
    progress: Float,
): RetainedSceneVisualTransform {
    val coercedProgress = progress.coerceIn(0f, 1f)
    return when (motionKind) {
        NavigationMotionKind.Forward -> if (role == ScenePresentationRole.Entering) {
            RetainedSceneVisualTransform(
                translationXFraction = (1f - coercedProgress) / 8f,
                alpha = coercedProgress,
            )
        } else {
            // The outgoing destination is the opaque backing surface for the entering page.
            RetainedSceneVisualTransform()
        }

        NavigationMotionKind.Backward -> if (role == ScenePresentationRole.Exiting) {
            RetainedSceneVisualTransform(
                translationXFraction = coercedProgress / 10f,
                alpha = 1f - coercedProgress,
                scale = 1f - 0.04f * coercedProgress,
            )
        } else {
            // The preview must cover all deeper retained history while the top page exits.
            RetainedSceneVisualTransform()
        }

        NavigationMotionKind.FadeThrough -> if (role == ScenePresentationRole.Entering) {
            RetainedSceneVisualTransform(
                alpha = coercedProgress,
                scale = 0.985f + 0.015f * coercedProgress,
            )
        } else {
            RetainedSceneVisualTransform()
        }

        NavigationMotionKind.MainForward,
        NavigationMotionKind.MainBackward,
        NavigationMotionKind.None -> RetainedSceneVisualTransform()
    }
}

private fun ContentSceneSnapshot.retainedKey(): RetainedSceneKey =
    currentEntry.retainedKey()

private fun org.milkdev.dreamplayer.navigation.NavigationEntry.retainedKey():
    RetainedSceneKey = if (route.toMainTabOrNull() != null) {
    RetainedSceneKey.RootCarousel
} else {
    RetainedSceneKey.Entry(entryId)
}

private fun NavigationMotionKind.retainedDurationMillis(): Int = when (this) {
    NavigationMotionKind.None -> 1
    NavigationMotionKind.Forward -> RetainedForwardDurationMillis
    NavigationMotionKind.Backward -> RetainedBackwardDurationMillis
    NavigationMotionKind.MainForward,
    NavigationMotionKind.MainBackward -> MainCarouselDurationMillis
    NavigationMotionKind.FadeThrough -> RetainedFadeThroughDurationMillis
}

private fun retainedBackSpring() = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = 0.001f,
)

private const val RootCarouselSaveableStateKey = "retained-root-carousel"
private const val RetainedForwardDurationMillis = 380
private const val RetainedBackwardDurationMillis = 340
private const val RetainedFadeThroughDurationMillis = 300
private const val RetainedTimeDrivenBackDurationMillis = 260
private val RetainedSceneTransitionEasing =
    CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
