package org.milkdev.dreamplayer.navigation

enum class NavigationOperation(val logName: String) {
    MainSwitch("main_switch"),
    Push("push"),
    Pop("pop"),
    SearchOpen("search_open"),
    SearchClose("search_close"),
    OverlayOpen("overlay_open"),
    OverlayClose("overlay_close"),
    OverlayReset("overlay_reset"),
}

enum class NavigationCause {
    Direct,
    Back,
    SystemCorrection,
}

data class NavigationTransaction(
    val id: Long,
    val operation: NavigationOperation,
    val cause: NavigationCause,
    val fromEntry: NavigationEntry,
    val toEntry: NavigationEntry,
    val fromContentEntry: NavigationEntry,
    val toContentEntry: NavigationEntry,
) {
    val affectsContent: Boolean
        get() = fromContentEntry.entryId != toContentEntry.entryId
}

data class AppNavigationSnapshot(
    val state: AppNavigationState = AppNavigationState(),
    val revision: Long = 0L,
    val lastTransaction: NavigationTransaction? = null,
)

class NavigationPlan internal constructor(
    val expectedRevision: Long,
    val expectedTopEntryId: Long,
    val targetState: AppNavigationState,
    val operation: NavigationOperation,
    val cause: NavigationCause,
)

fun AppNavigationSnapshot.planBack(
    expectedTopEntryId: Long? = null,
): NavigationPlan? {
    return AppNavigator.plan(
        this,
        NavigationIntent.Back(expectedTopEntryId),
    )
}

internal sealed interface NavigationIntent {
    data class SelectMainTab(
        val tab: MainTab,
    ) : NavigationIntent

    data class Push(
        val route: AppRoute,
    ) : NavigationIntent

    data object OpenSearch : NavigationIntent

    data class Back(
        val expectedTopEntryId: Long? = null,
    ) : NavigationIntent

    data object RemovePlaybackOverlays : NavigationIntent
}

internal data class NavigationCommit(
    val snapshot: AppNavigationSnapshot,
    val transaction: NavigationTransaction,
)

internal object AppNavigator {
    fun plan(
        snapshot: AppNavigationSnapshot,
        intent: NavigationIntent,
    ): NavigationPlan? {
        val currentState = snapshot.state
        val targetState = when (intent) {
            is NavigationIntent.SelectMainTab -> currentState.selectMainTab(intent.tab)
            is NavigationIntent.Push -> currentState.push(intent.route)
            NavigationIntent.OpenSearch -> currentState.openSearch()
            is NavigationIntent.Back -> currentState.navigateBack(intent.expectedTopEntryId)
            NavigationIntent.RemovePlaybackOverlays -> currentState.removePlaybackOverlays()
        } ?: return null
        if (targetState === currentState) return null

        return NavigationPlan(
            expectedRevision = snapshot.revision,
            expectedTopEntryId = currentState.currentEntry.entryId,
            targetState = targetState,
            operation = intent.operation(currentState),
            cause = intent.cause(),
        )
    }

    fun commit(
        snapshot: AppNavigationSnapshot,
        plan: NavigationPlan,
    ): NavigationCommit? {
        if (
            snapshot.revision != plan.expectedRevision ||
            snapshot.state.currentEntry.entryId != plan.expectedTopEntryId
        ) {
            return null
        }

        check(snapshot.revision < Long.MAX_VALUE) {
            "Navigation revision space is exhausted"
        }
        val nextRevision = snapshot.revision + 1L
        val transaction = NavigationTransaction(
            id = nextRevision,
            operation = plan.operation,
            cause = plan.cause,
            fromEntry = snapshot.state.currentEntry,
            toEntry = plan.targetState.currentEntry,
            fromContentEntry = snapshot.state.currentContentEntry,
            toContentEntry = plan.targetState.currentContentEntry,
        )
        return NavigationCommit(
            snapshot = AppNavigationSnapshot(
                state = plan.targetState,
                revision = nextRevision,
                lastTransaction = transaction,
            ),
            transaction = transaction,
        )
    }
}

private fun NavigationIntent.cause(): NavigationCause {
    return when (this) {
        is NavigationIntent.Back -> NavigationCause.Back
        NavigationIntent.RemovePlaybackOverlays -> NavigationCause.SystemCorrection
        is NavigationIntent.SelectMainTab,
        is NavigationIntent.Push,
        NavigationIntent.OpenSearch -> NavigationCause.Direct
    }
}

private fun NavigationIntent.operation(
    currentState: AppNavigationState,
): NavigationOperation {
    return when (this) {
        is NavigationIntent.SelectMainTab -> NavigationOperation.MainSwitch
        is NavigationIntent.Push -> if (route.isPlaybackOverlay) {
            NavigationOperation.OverlayOpen
        } else {
            NavigationOperation.Push
        }

        NavigationIntent.OpenSearch -> NavigationOperation.SearchOpen
        is NavigationIntent.Back -> when {
            currentState.currentDestination == AppRoute.Player ||
                currentState.currentDestination == AppRoute.Queue ->
                NavigationOperation.OverlayClose

            currentState.currentDestination == AppRoute.Search ->
                NavigationOperation.SearchClose

            currentState.backStack.size == 1 &&
                currentState.activeMainTab != MainTab.Home ->
                NavigationOperation.MainSwitch

            else -> NavigationOperation.Pop
        }

        NavigationIntent.RemovePlaybackOverlays -> NavigationOperation.OverlayReset
    }
}
