package org.example.project.scheduler.platform

// Browser builds are not a phone/desktop install, and there is no app-wide OS activity signal to observe.
actual fun currentDeviceKind(): DeviceKind = DeviceKind.Other

actual fun isScreenActive(): Boolean = false

actual fun installPlatformActivityListener(onChanged: () -> Unit) {}
