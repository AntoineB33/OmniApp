package org.example.project

import javax.swing.SwingUtilities

/**
 * Runs [block] on the AWT event thread — **every test that drives an `ImageComposeScene` does it there.**
 *
 * Compose's `GlobalSnapshotManager` sends the snapshot apply notifications on that thread, under the
 * `SnapshotStateObserver` lock of every live scene, and through it resumes the scene's `FlushCoroutineDispatcher`
 * (its lock). A scene rendered on the test thread takes the two locks in the other order: the JVM deadlocks, for
 * good (2026-11-15: `LateralMenuShiftTest` 39 min, `CheckBoxDropDownRenderTest` 28 min, under four test JVMs). One
 * thread for both, and the two can no longer cross.
 */
internal fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return result!!.getOrThrow()
}
