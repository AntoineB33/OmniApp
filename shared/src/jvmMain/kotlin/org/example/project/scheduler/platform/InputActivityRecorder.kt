package org.example.project.scheduler.platform

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.example.project.ui.InputActivity
import org.example.project.ui.SecondsByDay

/**
 * The desktop's [InputActivity] (user rule 2026-10-15): **every action the user does on Windows, to the second** — fed
 * by the low-level keyboard and mouse hooks the global-hotkey claim already runs ([GlobalHotkeyClaimant]), on its
 * message loop.
 *
 * The hooks are on the critical path of every input in the session, and Windows unhooks one that keeps it waiting
 * (`shortcuts.md`), so [noteInput] does the least there is: it reads the clock, and once per second at most queues that
 * second. Everything else — the bits, the files — happens on a thread of its own. Only the SECOND is kept: never which
 * key, never where the pointer was.
 *
 * Stored locally, never synced: one file of [SecondsByDay.BYTES_PER_DAY] bytes per UTC day in `input-activity/` of the
 * state directory, written at most every [FLUSH_DELAY_SECONDS] while there is something new to write, and kept
 * [KEPT_DAYS] days.
 */
object InputActivityRecorder : InputActivity {
    private val seconds = SecondsByDay()
    private val queue = ConcurrentLinkedQueue<Long>()
    private val draining = AtomicBoolean(false)
    private val dirtyDays = HashSet<Long>()
    private var flushScheduled = false
    private var dir: File? = null

    @Volatile
    private var lastSecond = -1L

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    private val worker: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "omniapp-input-activity").apply { isDaemon = true } }

    /** Reads what was recorded in [stateDir] (the last [KEPT_DAYS] days) and starts keeping what is recorded next. */
    fun start(stateDir: File) {
        worker.execute {
            val folder = File(stateDir, FOLDER).apply { mkdirs() }
            val today = System.currentTimeMillis() / 1000 / SecondsByDay.SECONDS_PER_DAY
            folder.listFiles()?.forEach { file ->
                val day = file.name.removeSuffix(SUFFIX).toLongOrNull() ?: return@forEach
                if (day < today - KEPT_DAYS) {
                    file.delete()
                } else {
                    runCatching { synchronized(seconds) { seconds.decodeDay(day, file.readBytes()) } }
                }
            }
            dir = folder
            _revision.value++
        }
        // What is still unwritten when the app goes is written on its way out.
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { flush() } }, "omniapp-input-activity-flush"))
    }

    /** A hook saw an action. Called on the hook's thread: a clock read, and a queue add once per second. */
    fun noteInput() {
        val second = System.currentTimeMillis() / 1000
        if (second == lastSecond) return
        lastSecond = second
        queue.add(second)
        if (draining.compareAndSet(false, true)) worker.execute(::drain)
    }

    private fun drain() {
        draining.set(false)
        var added = false
        while (true) {
            val second = queue.poll() ?: break
            synchronized(seconds) {
                if (seconds.add(second)) {
                    dirtyDays += second.floorDiv(SecondsByDay.SECONDS_PER_DAY)
                    added = true
                }
            }
        }
        if (!added) return
        _revision.value++
        if (!flushScheduled) {
            flushScheduled = true
            worker.schedule(::flush, FLUSH_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun flush() {
        flushScheduled = false
        val folder = dir ?: return
        val days = synchronized(seconds) { dirtyDays.toList().also { dirtyDays.clear() } }
        for (day in days) {
            val bytes = synchronized(seconds) { seconds.encodeDay(day) } ?: continue
            runCatching {
                // Written beside, then moved over: a file is never seen half written.
                val part = File(folder, "$day$SUFFIX.part")
                part.writeBytes(bytes)
                java.nio.file.Files.move(
                    part.toPath(), File(folder, "$day$SUFFIX").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }.onFailure { Diagnostics.log("input activity: day $day not written: ${it.message}") }
        }
    }

    override fun forEachSecond(fromSecond: Long, untilSecond: Long, each: (Long) -> Unit) {
        synchronized(seconds) { seconds.forEachSecond(fromSecond, untilSecond, each) }
    }

    private const val FOLDER = "input-activity"
    private const val SUFFIX = ".bin"
    private const val FLUSH_DELAY_SECONDS = 30L
    private const val KEPT_DAYS = 90L
}
