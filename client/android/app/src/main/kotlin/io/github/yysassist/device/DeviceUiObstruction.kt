package io.github.yysassist.device

import java.io.Closeable

/** Main-thread UI owner. A lease excludes our windows; it is not proof of input quiescence. */
internal interface DeviceUiObstruction {
    fun excludeCapture(ready: (Closeable) -> Unit): Closeable
    fun excludeGesture(points: List<ScreenPoint>, ready: (Closeable) -> Unit): Closeable
}

/**
 * Several of our windows that must all step aside (the floating ball, the 待命 screen saver): each is excluded in turn,
 * and the capture or gesture goes ahead once every one is out of the way. Closing releases them all, last first.
 */
internal class CombinedObstruction(private val parts: List<DeviceUiObstruction>) : DeviceUiObstruction {
    override fun excludeCapture(ready: (Closeable) -> Unit): Closeable = all({ part, next -> part.excludeCapture(next) }, ready)
    override fun excludeGesture(points: List<ScreenPoint>, ready: (Closeable) -> Unit): Closeable =
        all({ part, next -> part.excludeGesture(points, next) }, ready)

    private fun all(exclude: (DeviceUiObstruction, (Closeable) -> Unit) -> Closeable, ready: (Closeable) -> Unit): Closeable {
        val leases = mutableListOf<Closeable>()
        var released = false
        val combined = Closeable { if (!released) { released = true; leases.asReversed().forEach { it.close() } } }
        fun hold(lease: Closeable) { if (leases.none { it === lease }) leases += lease; if (released) lease.close() }
        fun next(index: Int) {
            if (released) return
            if (index == parts.size) { ready(combined); return }
            hold(exclude(parts[index]) { lease -> hold(lease); next(index + 1) })
        }
        next(0)
        return combined
    }
}
