package org.example.project.scheduler.domain

/** The operating system a workplace setup is made for. A setup is applied on that one alone. */
enum class WorkplaceOs(val label: String) {
    Windows11("Windows 11"),
}

/** What a window of a workplace setup is. */
enum class WorkplaceApp {
    FileExplorer,
    VsCode,
}

/**
 * One window of a workplace setup, on the paths the user typed in [tabs]: a file explorer with a tab on each, or (user rule
 * 2026-11-02) VS Code on the one folder.
 */
data class WorkplaceWindow(val tabs: List<String> = listOf(""), val app: WorkplaceApp = WorkplaceApp.FileExplorer)

/**
 * User rule 2026-11-01: **a workplace setup**: windows of the operating system [os] to open at one press, *"to quickly
 * open a file explorer window with two tabs on specific paths on Windows 11"*. An element of the Search window, found by
 * default on the Settings page. Authoritative: persisted and synced like a chrono, each device applying what is its own.
 */
data class WorkplaceSetup(
    val id: String,
    val title: String = "",
    val os: WorkplaceOs = WorkplaceOs.Windows11,
    val windows: List<WorkplaceWindow> = listOf(WorkplaceWindow()),
)

/** A file explorer window open on this device: its [handle] and the path of each of its tabs. */
data class OpenExplorerWindow(val handle: Long, val paths: List<String>)

/**
 * What is left to do for one window of a setup: [paths] opened as tabs of the window [handle], of a new one where null; or,
 * [app] being VS Code, the one folder opened.
 */
data class WorkplaceStep(val handle: Long?, val paths: List<String>, val app: WorkplaceApp = WorkplaceApp.FileExplorer)

object WorkplaceDomain {
    /** A new id, none of [used]. */
    fun mintId(used: Collection<String>): String {
        val taken = used.toHashSet()
        return generateSequence(1) { it + 1 }.map { "workplace-$it" }.first { it !in taken }
    }

    /** What a setup's row says beside its name. */
    fun detail(setup: WorkplaceSetup): String {
        val filled = setup.windows.filter { window -> window.tabs.any { it.isNotBlank() } }
        val (code, explorers) = filled.partition { it.app == WorkplaceApp.VsCode }
        val tabs = explorers.sumOf { window -> window.tabs.count { it.isNotBlank() } }
        val parts =
            listOfNotNull(
                count(explorers.size, "window").takeIf { explorers.isNotEmpty() || code.isEmpty() },
                count(tabs, "tab").takeIf { explorers.isNotEmpty() || code.isEmpty() },
                count(code.size, "VS Code window").takeIf { code.isNotEmpty() },
            )
        return setup.os.label + " · " + parts.joinToString(", ")
    }

    private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

    /** Whether [setup] can be applied on a device of the OS [here] (null: one that applies none): it has a path, for it. */
    fun canApply(setup: WorkplaceSetup, here: WorkplaceOs?): Boolean =
        setup.os == here && setup.windows.any { window -> window.tabs.any { it.isNotBlank() } }

    /** [setups] with one path changed; the [SchedulerIntent] of an editor is what it hands the reducer. */
    fun withWindows(setups: List<WorkplaceSetup>, id: String, windows: List<WorkplaceWindow>): List<WorkplaceSetup> =
        setups.map { if (it.id == id) it.copy(windows = windows) else it }

    /** [text] as the keys that type it: `SendKeys` reads these characters as commands unless they are braced. */
    fun sendKeysText(text: String): String =
        buildString {
            for (c in text) if (c in SEND_KEYS_SPECIAL) append('{').append(c).append('}') else append(c)
        }

    private const val SEND_KEYS_SPECIAL = "+^%~(){}[]"

    /** [text] as a single-quoted PowerShell string, in which nothing is read but the quote. */
    fun powerShellQuoted(text: String): String = "'" + text.replace("'", "''") + "'"

    /** [path] as the same folder reads, however it was typed: Windows' paths ignore case and a final separator. */
    fun samePath(path: String): String {
        val trimmed = path.trim().replace('/', '\\').trimEnd('\\').lowercase()
        return if (trimmed.length == 2 && trimmed[1] == ':') trimmed + "\\" else trimmed
    }

    /** The lines of [WINDOWS_11_LISTING], each `handle<tab>path`, as the windows they name in their order. */
    fun openWindows(listing: String): List<OpenExplorerWindow> =
        listing.lineSequence()
            .mapNotNull { line ->
                val handle = line.substringBefore('\t', "").trim().toLongOrNull() ?: return@mapNotNull null
                handle to line.substringAfter('\t').trim()
            }
            .filter { it.second.isNotEmpty() }
            .groupBy({ it.first }, { it.second })
            .map { (handle, paths) -> OpenExplorerWindow(handle, paths) }

    /**
     * User rule 2026-11-02, **what applying [setup] still has to open, [open] being on the screen**: nothing for a window
     * of the setup one open window already holds whole; else the tabs it lacks in the open window holding **the fewest of
     * its paths, at least one** (the first on a tie), which then holds them all; a new window where none holds any. An open
     * window serves one window of the setup. A VS Code window is always asked for: VS Code itself brings forward the window
     * already on a folder rather than open a second one.
     */
    fun plan(setup: WorkplaceSetup, open: List<OpenExplorerWindow>): List<WorkplaceStep> {
        val free = open.toMutableList()
        return setup.windows.flatMap { window ->
            val required = window.tabs.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::samePath)
            if (window.app == WorkplaceApp.VsCode) return@flatMap required.map { WorkplaceStep(null, listOf(it), WorkplaceApp.VsCode) }
            if (required.isEmpty()) return@flatMap emptyList()
            fun held(candidate: OpenExplorerWindow): Int {
                val theirs = candidate.paths.mapTo(HashSet(), ::samePath)
                return required.count { samePath(it) in theirs }
            }
            val whole = free.firstOrNull { held(it) == required.size }
            val target = whole ?: free.filter { held(it) > 0 }.minByOrNull(::held)
            target?.let(free::remove)
            when {
                whole != null -> emptyList()
                target == null -> listOf(WorkplaceStep(null, required))
                else -> {
                    val theirs = target.paths.mapTo(HashSet(), ::samePath)
                    listOf(WorkplaceStep(target.handle, required.filter { samePath(it) !in theirs }))
                }
            }
        }
    }

    /**
     * **The PowerShell that does [steps] on Windows 11.** Windows has no command for a tab of the file explorer, so a
     * window is opened on its first path and each other tab is typed into it: Ctrl+T, the address bar, the path. Before
     * every keystroke the script asks whether that window is still the one in front, and stops if not: the keys of a
     * window that lost the foreground would go to whatever took it.
     */
    fun windows11Script(steps: List<WorkplaceStep>): String =
        buildString {
            append(WINDOWS_11_PRELUDE)
            for (step in steps) {
                val first = step.paths.firstOrNull() ?: continue
                val typed = { paths: List<String> -> paths.joinToString(", ") { powerShellQuoted(sendKeysText(it)) } }
                if (step.app == WorkplaceApp.VsCode) {
                    append("Open-VsCode ").append(powerShellQuoted(first)).append("\n")
                } else if (step.handle == null) {
                    append("Open-ExplorerWindow ").append(powerShellQuoted(first)).append(" @(").append(typed(step.paths.drop(1))).append(")\n")
                } else {
                    append("Add-ExplorerTabs ").append(step.handle).append(" @(").append(typed(step.paths)).append(")\n")
                }
            }
        }

    /** [setup] opened with nothing on the screen. */
    fun windows11Script(setup: WorkplaceSetup): String = windows11Script(plan(setup, emptyList()))

    /** The PowerShell that lists every tab of the file explorer; it opens nothing. Windows lists each tab, under its window. */
    val WINDOWS_11_LISTING =
        """
        |${'$'}ErrorActionPreference = 'Stop'
        |[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        |trap { [Console]::Out.WriteLine(${'$'}_.Exception.Message); exit 1 }
        |${'$'}shell = New-Object -ComObject Shell.Application
        |@(${'$'}shell.Windows()) | Where-Object { ${'$'}_.FullName -like '*explorer.exe' } | ForEach-Object {
        |    ${'$'}path = try { ${'$'}_.Document.Folder.Self.Path } catch { ${'$'}null }
        |    if (${'$'}path) { [string][long]${'$'}_.HWND + "`t" + ${'$'}path }
        |}
        |
        """.trimMargin()

    private val WINDOWS_11_PRELUDE =
        """
        |${'$'}ErrorActionPreference = 'Stop'
        |[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        |trap { [Console]::Out.WriteLine(${'$'}_.Exception.Message); exit 1 }
        |Add-Type -AssemblyName System.Windows.Forms
        |Add-Type -TypeDefinition @'
        |using System;
        |using System.Runtime.InteropServices;
        |public static class OmniForeground {
        |    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
        |    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
        |    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
        |    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr window, int command);
        |}
        |'@
        |${'$'}shell = New-Object -ComObject Shell.Application
        |function Get-ExplorerWindows {
        |    @(${'$'}shell.Windows() | Where-Object { ${'$'}_.FullName -like '*explorer.exe' } | ForEach-Object { [long]${'$'}_.HWND }) | Select-Object -Unique
        |}
        |function Assert-Foreground([long]${'$'}window) {
        |    [void][OmniForeground]::SetForegroundWindow([IntPtr]${'$'}window)
        |    Start-Sleep -Milliseconds 150
        |    if ([long][OmniForeground]::GetForegroundWindow() -ne ${'$'}window) { throw 'The file explorer window lost the foreground: the other tabs were not opened.' }
        |}
        |function Send-Keys([long]${'$'}window, [string]${'$'}keys, [int]${'$'}wait) {
        |    Assert-Foreground ${'$'}window
        |    [System.Windows.Forms.SendKeys]::SendWait(${'$'}keys)
        |    Start-Sleep -Milliseconds ${'$'}wait
        |}
        |function Open-ExplorerWindow([string]${'$'}first, [string[]]${'$'}others) {
        |    ${'$'}before = @(Get-ExplorerWindows)
        |    Start-Process explorer.exe -ArgumentList ('"' + ${'$'}first + '"')
        |    ${'$'}window = ${'$'}null
        |    for (${'$'}i = 0; ${'$'}i -lt 50 -and -not ${'$'}window; ${'$'}i++) {
        |        Start-Sleep -Milliseconds 200
        |        ${'$'}window = Get-ExplorerWindows | Where-Object { ${'$'}before -notcontains ${'$'}_ } | Select-Object -First 1
        |    }
        |    if (-not ${'$'}window) { throw 'No new file explorer window opened.' }
        |    Add-Tabs ${'$'}window ${'$'}others
        |}
        |function Add-ExplorerTabs([long]${'$'}window, [string[]]${'$'}paths) {
        |    if ([OmniForeground]::IsIconic([IntPtr]${'$'}window)) { [void][OmniForeground]::ShowWindow([IntPtr]${'$'}window, 9) }
        |    Add-Tabs ${'$'}window ${'$'}paths
        |}
        |function Open-VsCode([string]${'$'}folder) {
        |    ${'$'}command = Get-Command code.cmd -ErrorAction SilentlyContinue
        |    ${'$'}program = if (${'$'}command) { Join-Path (Split-Path (Split-Path ${'$'}command.Source)) 'Code.exe' } else { Join-Path ${'$'}env:LOCALAPPDATA 'Programs\Microsoft VS Code\Code.exe' }
        |    if (-not (Test-Path -LiteralPath ${'$'}program)) { throw 'VS Code was not found on this device.' }
        |    if (-not (Test-Path -LiteralPath ${'$'}folder -PathType Container)) { throw ('No such folder: ' + ${'$'}folder) }
        |    if (${'$'}folder.Length -gt 3) { ${'$'}folder = ${'$'}folder.TrimEnd('\') }
        |    Start-Process ${'$'}program -ArgumentList ('"' + ${'$'}folder + '"')
        |}
        |function Add-Tabs([long]${'$'}window, [string[]]${'$'}paths) {
        |    foreach (${'$'}path in ${'$'}paths) {
        |        Send-Keys ${'$'}window '^t' 700
        |        Send-Keys ${'$'}window '^l' 300
        |        Send-Keys ${'$'}window ${'$'}path 150
        |        Send-Keys ${'$'}window '{ENTER}' 500
        |    }
        |}
        |
        """.trimMargin()
}
