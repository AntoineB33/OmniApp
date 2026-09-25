package org.example.project.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import org.example.project.scheduler.persistence.WindowPlacement
import org.example.project.scheduler.persistence.WindowPlacementStore
import androidx.compose.ui.window.WindowPlacement as OsWindowPlacement

/**
 * The row the desktop app's OWN window is kept on — the OS window the whole app is drawn in, as opposed to the
 * windows drawn inside it. The same local store and the same rule: local-only view state, never synced
 * (`popups.md`, *Geometry is local-only view state*). No window inside the app has this frame id.
 */
const val APP_WINDOW_PLACEMENT_ID: String = "AppWindow"

/**
 * Where the app's window stood and how big it was when NOT maximized (in dp, the OS window's own units), and
 * whether it was maximized. The normal bounds are kept while it is maximized, so un-maximizing after a restart
 * goes back to them, as it would have without the restart.
 */
data class AppWindowBounds(val x: Float, val y: Float, val width: Float, val height: Float, val maximized: Boolean) {
    fun toPlacement(): WindowPlacement =
        WindowPlacement(x = x, y = y, width = width, height = height, visible = true, fillWidth = maximized, fillHeight = maximized)

    /**
     * Whether a window at these bounds would show enough of its title bar on one of [screens] to be grabbed. A
     * screen unplugged since (or a smaller resolution) would otherwise bring the app back where nobody can reach it.
     */
    fun reachableOn(screens: List<Rectangle>): Boolean {
        val titleBar = Rectangle(x.toInt(), y.toInt(), width.toInt(), TITLE_BAR_HEIGHT)
        return screens.any { screen ->
            val shown = screen.intersection(titleBar)
            !shown.isEmpty && shown.width >= MIN_GRAB_WIDTH
        }
    }

    companion object {
        private const val TITLE_BAR_HEIGHT = 30
        private const val MIN_GRAB_WIDTH = 100

        fun of(placement: WindowPlacement?): AppWindowBounds? =
            placement?.takeIf { it.width > 0f && it.height > 0f }?.let {
                AppWindowBounds(it.x, it.y, it.width, it.height, maximized = it.fillWidth && it.fillHeight)
            }
    }
}

/** The screens' bounds, in the coordinates the OS window is placed in. */
private fun screenBounds(): List<Rectangle> =
    runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds } }
        .getOrDefault(emptyList())

/**
 * What is kept after the window settled: its normal bounds as they now are ([floating], the OS window's own bounds,
 * while it is not maximized), marked [maximized] — or, maximized, the normal bounds last known. A window maximized
 * before it was ever seen floating (a first launch, maximized at once) has none yet, and still has to be kept
 * maximized: it is given the default size, centred on its [screen].
 */
internal fun settledAppWindowBounds(
    normal: AppWindowBounds?,
    maximized: Boolean,
    floating: Rectangle?,
    screen: Rectangle?,
): AppWindowBounds? =
    when {
        !maximized && floating != null ->
            AppWindowBounds(
                floating.x.toFloat(), floating.y.toFloat(), floating.width.toFloat(), floating.height.toFloat(),
                maximized = false,
            )
        !maximized -> normal
        normal != null -> normal.copy(maximized = true)
        screen != null ->
            AppWindowBounds(
                x = screen.x + (screen.width - DEFAULT_WIDTH) / 2f,
                y = screen.y + (screen.height - DEFAULT_HEIGHT) / 2f,
                width = DEFAULT_WIDTH,
                height = DEFAULT_HEIGHT,
                maximized = true,
            )
        else -> null
    }

private const val DEFAULT_WIDTH = 800f
private const val DEFAULT_HEIGHT = 600f

/** The app window's state, and what was kept of it — see [rememberPersistedAppWindow] and [KeepAppWindowPlacement]. */
class PersistedAppWindow internal constructor(
    val state: WindowState,
    internal val saved: AppWindowBounds?,
    internal val store: WindowPlacementStore?,
)

/**
 * The app window's state, opened as it was left: its normal position and size, and maximized if it was (a place
 * no screen shows any more is dropped for the platform's default). Minimized is not kept: the app comes back on
 * screen. [KeepAppWindowPlacement], inside the window, is what keeps it.
 */
@Composable
fun rememberPersistedAppWindow(store: WindowPlacementStore?): PersistedAppWindow {
    val saved = remember(store) { AppWindowBounds.of(store?.loadPlacements()?.get(APP_WINDOW_PLACEMENT_ID)) }
    val placeable = remember(saved) { saved?.takeIf { it.reachableOn(screenBounds()) } }
    val state = rememberWindowState(
        placement = if (saved?.maximized == true) OsWindowPlacement.Maximized else OsWindowPlacement.Floating,
        position = placeable?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
        size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: DpSize(DEFAULT_WIDTH.dp, DEFAULT_HEIGHT.dp),
    )
    return remember(state) { PersistedAppWindow(state, saved, store) }
}

/**
 * Keeps the app window's placement, from INSIDE the window: every change is written once it settles — the end of
 * a drag, a resize, a maximize — never mid-gesture. The normal bounds are read off the OS window itself, not off
 * [WindowState.position], which stays `PlatformDefault` for a window the user never moved: reading that instead
 * is how a first launch maximized straight away was never kept (2026-09-25).
 */
@OptIn(FlowPreview::class)
@Composable
fun FrameWindowScope.KeepAppWindowPlacement(appWindow: PersistedAppWindow) {
    val store = appWindow.store ?: return
    val state = appWindow.state
    LaunchedEffect(appWindow) {
        var normal = appWindow.saved
        snapshotFlow { listOf(state.placement, state.position, state.size, state.isMinimized) }
            .debounce(SETTLE_MILLIS)
            .collect {
                // A minimized window's bounds are the OS's parking spot (-32000 on Windows), not a place.
                if (state.isMinimized) return@collect
                val maximized = state.placement != OsWindowPlacement.Floating
                val next = settledAppWindowBounds(
                    normal,
                    maximized,
                    floating = if (maximized) null else window.bounds,
                    screen = window.graphicsConfiguration?.bounds,
                ) ?: return@collect
                if (next != normal) {
                    normal = next
                    store.savePlacement(APP_WINDOW_PLACEMENT_ID, next.toPlacement())
                }
            }
    }
}

/** How long the window must stand still before its placement is written — once per gesture, not per pixel. */
private const val SETTLE_MILLIS = 500L
