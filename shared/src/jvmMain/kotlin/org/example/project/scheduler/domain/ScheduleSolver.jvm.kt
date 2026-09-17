package org.example.project.scheduler.domain

import org.example.project.scheduler.platform.Diagnostics

/**
 * The desktop's solver: [MipScheduleSolver] over OR-Tools' SCIP, when the native library loads. It loads once per
 * process; a JVM where it cannot (not Windows x86-64, a stripped runtime) plans without a platform solver, exactly as
 * the phone does.
 */
internal actual fun platformScheduleSolver(): ExternalScheduleSolver? = MipScheduleSolver.instance

internal object OrTools {
    /** Whether OR-Tools' native library is loaded and SCIP is available. Asked once. */
    val available: Boolean by lazy {
        runCatching {
            com.google.ortools.Loader.loadNativeLibraries()
            val probe = com.google.ortools.linearsolver.MPSolver.createSolver(MipScheduleSolver.BACKEND)
            val ok = probe != null
            probe?.delete()
            ok
        }.onFailure { Diagnostics.log("scheduler solver: OR-Tools unavailable (${it::class.simpleName}: ${it.message})") }
            .getOrDefault(false)
    }
}
