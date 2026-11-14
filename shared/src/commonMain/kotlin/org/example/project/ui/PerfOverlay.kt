package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.project.perf.Perf
import org.example.project.perf.PerfLeakWatch
import org.example.project.perf.PerfReport
import org.example.project.perf.PerfSnapshot
import org.example.project.scheduler.platform.Diagnostics
import kotlin.coroutines.coroutineContext

private val perfAccent = Color(0xFF00897B) // teal — distinct from the sim panel's purple and the calendar blue

/** How often the overlay reads the instruments. One second makes every rate directly a "per second". */
private const val REFRESH_MILLIS = 1000L

/**
 * Drives the frame meter: one `withFrameNanos` per frame, forever.
 *
 * This is the one thing in the app that is deliberately a per-frame loop, and it is sound because
 * `withFrameNanos` **observes** the frame clock rather than requesting frames — it resumes on frames Compose
 * was going to produce anyway and never schedules one, so a fully idle app still measures 0 fps rather than
 * being pinned at 60 by its own meter. That distinction is the whole reason this is not a `delay(16)` loop,
 * and it is why the number it reports means "how fast is the app drawing when it draws".
 *
 * Compose only. Placed once, by [PerfOverlay].
 */
@Composable
private fun PerfFrameMeter() {
    LaunchedEffect(Unit) {
        var previous = 0L
        while (coroutineContext.isActive) {
            withFrameNanos { frameTime ->
                if (previous != 0L) Perf.frame(frameTime - previous)
                previous = frameTime
            }
        }
    }
}

/**
 * Debug-only performance overlay (gated by [org.example.project.DebugFlags.PERF]).
 *
 * Shows, once a second: the frame rate and the worst frame, the process CPU and heap, and the sections and
 * counters ranked by **milliseconds spent per wall-clock second** — which is the number that answers "is the
 * app doing unwanted work", because a derivation that costs 4 ms is fine at 1 Hz and is 40% of a core at
 * 100 Hz, and only the rate distinguishes them.
 *
 * Buttons:
 *  * **reset** — zero every accumulator, so a measurement can be scoped to one gesture ("reset, then drag
 *    the calendar for five seconds").
 *  * **gc** — force a collection and take a leak sample now, instead of waiting out the interval.
 *  * **dump** — write the full report to the diagnostics timeline, where `collect-diagnostics.bat` merges it
 *    with the calendar bands and the scripts' markers.
 */
@Composable
fun PerfOverlay(
    /** Wall-clock instant for the leak samples — the app's own clock, so a sim run stamps consistently. */
    nowMillis: Long,
    /** How long between forced-GC leak samples. Each one stalls the app, so it is deliberately long. */
    leakSampleIntervalMillis: Long = PerfLeakWatch.DEFAULT_INTERVAL_MILLIS,
    modifier: Modifier = Modifier,
) {
    PerfFrameMeter()

    // One line on the diagnostics timeline saying the profiler was up. `collect-diagnostics.bat` merges that
    // timeline across devices and scripts, and a stretch of it recorded under the recorder is not comparable
    // with the rest — the leak sampler forces collections, and every instrumented site is live. Marking the
    // window is what keeps a later reader from treating a profiled run as a normal one.
    LaunchedEffect(Unit) {
        Diagnostics.log("perf overlay started (recorder ${if (Perf.enabled) "on" else "OFF — DebugFlags.PERF not set"})")
    }

    var offset by remember { mutableStateOf(Offset.Zero) }
    var expanded by remember { mutableStateOf(false) }
    // The previous reading, so what is displayed is the DIFF: totals are dominated by whatever ran at
    // startup, and a rate is the only form in which a cost can be compared against the frame budget.
    var previous by remember { mutableStateOf<PerfSnapshot?>(null) }
    var window by remember { mutableStateOf<PerfSnapshot?>(null) }
    var total by remember { mutableStateOf<PerfSnapshot?>(null) }
    var lastDump by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            delay(REFRESH_MILLIS)
            val current = Perf.snapshot()
            window = current.since(previous)
            total = current
            previous = current
        }
    }

    // The leak sampler. Separate from the readout loop and orders of magnitude slower, because a sample
    // forces two collections: at the readout's cadence it would be the app's dominant cost and would
    // measure mostly itself. It runs OFF the UI thread for the same reason — measuring the app must not be
    // what drops a frame, or the frame meter ends up reporting the profiler.
    LaunchedEffect(leakSampleIntervalMillis) {
        while (coroutineContext.isActive) {
            withContext(Dispatchers.Default) { PerfLeakWatch.sample(nowMillis) }
            delay(leakSampleIntervalMillis)
        }
    }
    val scope = rememberCoroutineScope()

    val w = window
    val t = total
    Surface(
        color = Color(0xFF10201E),
        contentColor = Color(0xFFE0F2F1),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .border(1.dp, perfAccent, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    offset += drag
                }
            }
            .widthIn(max = 560.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "perf",
                    color = perfAccent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
                val frames = w?.frames
                Text(
                    if (frames == null || frames.sampled == 0) {
                        "measuring…"
                    } else {
                        "${fixed(frames.fps, 0)} fps   p95 ${fixed(frames.p95Nanos / 1e6, 1)}ms   " +
                            "jank ${frames.jankFrames}"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                Box(modifier = Modifier.weight(1f))
                PerfButton(if (expanded) "less" else "more") { expanded = !expanded }
                PerfButton("reset") {
                    Perf.reset()
                    PerfLeakWatch.clear()
                    previous = null
                }
                PerfButton("gc") { scope.launch { withContext(Dispatchers.Default) { PerfLeakWatch.sample(nowMillis) } } }
                PerfButton("dump") {
                    if (w != null && t != null) {
                        val text = PerfReport.format("perf dump", w, t)
                        PerfReport.dumpToDiagnostics(text)
                        lastDump = "written to diagnostics.log"
                    }
                }
            }
            if (w != null && t != null) {
                val p = w.platform
                Text(
                    "cpu ${if (p.processCpuLoad < 0) "n/a" else fixed(p.processCpuLoad * 100, 0) + "%"}" +
                        "   heap ${fixed(p.heapUsedBytes / 1048576.0, 0)}/" +
                        "${fixed(p.heapMaxBytes / 1048576.0, 0)}MB" +
                        "   gc ${p.gcCount}" +
                        "   threads ${p.threadCount}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                val seconds = (w.uptimeNanos / 1e9).coerceAtLeast(0.001)
                // The top few by ms/s: the whole point of the collapsed view is that the worst offender is
                // visible without opening anything, so a glance while dragging a window is enough.
                val ranked = w.sections
                    .filter { it.calls > 0 }
                    .map { it to it.totalNanos / 1e6 / seconds }
                    .sortedByDescending { it.second }
                    .take(if (expanded) 18 else 5)
                ranked.forEach { (stat, msPerSecond) ->
                    Text(
                        line(stat.name, 30) +
                            pad(fixed(msPerSecond, 1) + "ms/s", 11) +
                            pad(fixed(stat.calls / seconds, 1) + "/s", 9) +
                            "max " + fixed(stat.maxNanos / 1e6, 1) + "ms",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (msPerSecond >= 100.0) Color(0xFFFF8A65) else Color(0xFFB2DFDB),
                    )
                }
                if (expanded) {
                    Text(
                        "counters/s",
                        color = perfAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(
                        modifier = Modifier.verticalScrollWithBar(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        w.counters.filter { it.calls > 0 }.sortedByDescending { it.calls }.forEach {
                            Text(
                                line(it.name, 30) + pad(fixed(it.calls / seconds, 1), 9) + "(${it.calls})",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                        t.gauges.sortedByDescending { it.value }.forEach {
                            Text(
                                line("size " + it.name, 30) + pad(it.value.toString(), 9) + "peak ${it.max}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF80CBC4),
                            )
                        }
                        PerfLeakWatch.trends().take(6).forEach {
                            Text(
                                line("growth " + it.name, 30) + fixed(it.perHour, 1) + "/h",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = if (it.perHour > 0) Color(0xFFFFB74D) else Color(0xFF80CBC4),
                            )
                        }
                    }
                }
            }
            lastDump?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = perfAccent)
            }
        }
    }
}

@Composable
private fun PerfButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = perfAccent,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF1B3B37))
            .padding(horizontal = 5.dp, vertical = 1.dp)
            // Its own tap handler, consuming the press before the panel-wide drag above it can claim it.
            .pointerInput(label) { detectTapGestures { onClick() } },
    )
}

/** Left-aligned name, truncated from the LEFT so `display.…` prefixes never hide the distinguishing tail. */
private fun line(name: String, width: Int): String {
    val shown = if (name.length <= width) name else "…" + name.substring(name.length - width + 1)
    return pad(shown, width + 1)
}

private fun pad(s: String, width: Int) = if (s.length >= width) s + " " else s + " ".repeat(width - s.length)

private fun fixed(v: Double, decimals: Int): String {
    if (v.isNaN() || v.isInfinite()) return "n/a"
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = kotlin.math.round(v * scale).toLong()
    val negative = scaled < 0
    val abs = if (negative) -scaled else scaled
    val whole = abs / scale
    if (decimals == 0) return (if (negative) "-" else "") + whole
    val frac = (abs % scale).toString().padStart(decimals, '0')
    return (if (negative) "-" else "") + whole + "." + frac
}
