package io.github.yysassist.device

import java.io.Closeable

/** Main-thread UI owner. A lease excludes our windows; it is not proof of input quiescence. */
internal interface DeviceUiObstruction {
    fun excludeCapture(ready: (Closeable) -> Unit): Closeable
    fun excludeGesture(points: List<ScreenPoint>, ready: (Closeable) -> Unit): Closeable
}
