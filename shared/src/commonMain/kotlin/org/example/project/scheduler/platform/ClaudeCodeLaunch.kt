package org.example.project.scheduler.platform

/**
 * User rule 2026-11-21: the md elements' "Ask Claude Code" action. Whether this device opens Claude Code: a capability of
 * the build's source set (`docs/PLATFORMS.md`).
 */
expect fun claudeCodeOpensHere(): Boolean

/**
 * Writes [files] (`name → text`, [org.example.project.scheduler.domain.MdDomain.claudeFolderFiles]) into the device's md
 * folder — its `.md` files written before are taken out first, so a removed md element leaves no file — and opens Claude
 * Code in a terminal there, off the caller's thread. [onDone] is told the folder, or what went wrong. It opens a window: only
 * ever the user's own press.
 */
expect fun openClaudeCodeOn(files: Map<String, String>, onDone: (folder: String?, error: String?) -> Unit)
