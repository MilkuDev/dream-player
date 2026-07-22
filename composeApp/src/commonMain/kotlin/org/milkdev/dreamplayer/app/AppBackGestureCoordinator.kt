package org.milkdev.dreamplayer.app

internal enum class AppBackSurface {
    Content,
    Player,
    Queue,
}

internal data class AppBackGesture(
    val surface: AppBackSurface,
    val expectedTopEntryId: Long,
)

/**
 * Keeps one platform gesture bound to the surface that accepted its start.
 * Route changes during the gesture must not redirect its progress or terminal event.
 */
internal class AppBackGestureCoordinator {
    private sealed interface ActiveGesture {
        data class Routed(
            val gesture: AppBackGesture,
        ) : ActiveGesture

        data object Consumed : ActiveGesture
    }

    private var activeGesture: ActiveGesture? = null

    val routedGesture: AppBackGesture?
        get() = (activeGesture as? ActiveGesture.Routed)?.gesture

    fun begin(
        gesture: AppBackGesture,
        acceptedBySurface: Boolean,
    ) {
        if (activeGesture != null) return
        activeGesture = if (acceptedBySurface) {
            ActiveGesture.Routed(gesture)
        } else {
            ActiveGesture.Consumed
        }
    }

    fun finish(): AppBackGesture? {
        val gesture = routedGesture
        activeGesture = null
        return gesture
    }
}
