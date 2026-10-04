package org.example.project.scheduler.platform

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// The browser build's outputs (JS and Wasm share this file): clipboard, notifications, voice and the alarm ring.
// Each is best-effort — a browser that lacks the API, or a user who refused its permission, gets silence, never an
// error — and none of them asks anything of the server. `docs/PLATFORMS.md` lists what a browser cannot do.

// --- Clipboard -------------------------------------------------------------------------------------------------

/**
 * The text this page last put on the clipboard. A browser only reads the clipboard ASYNCHRONOUSLY, behind a
 * permission prompt, and the tree's paste is a synchronous key handler — so a paste here reads back what the app
 * itself copied or cut. A copy made in another application is not seen (`docs/PLATFORMS.md`).
 */
private var lastWrittenClipboardText: String? = null

actual fun readSystemClipboardText(): String? = lastWrittenClipboardText

actual fun writeSystemClipboardText(text: String) {
    lastWrittenClipboardText = text
    jsWriteClipboard(text)
}

private fun jsWriteClipboard(text: String): Unit =
    js("(function(){ try { if (navigator.clipboard) navigator.clipboard.writeText(text).catch(function(){}); } catch (e) {} })()")

// --- Notifications ---------------------------------------------------------------------------------------------

/**
 * PRD §11 through the Web Notifications API. Permission is asked on the first notification (and granted once,
 * by the user, for the origin); a click focuses the tab and reports [target] like every other platform's
 * ([NotificationClicks]). A phone's browser refuses page-made notifications (they need a service worker): the
 * spoken half still arrives.
 */
actual fun sendSystemNotification(title: String, message: String, target: NotificationTarget) {
    jsNotify(title, message) { NotificationClicks.clicked(target) }
}

actual fun cancelSystemNotifications() = jsCancelNotifications()

private fun jsNotify(title: String, body: String, onClick: () -> Unit): Unit =
    js(
        """(function(){
  if (typeof Notification === 'undefined') return;
  var show = function(){
    try {
      var n = new Notification(title, { body: body });
      n.onclick = function(){ try { window.focus(); } catch (e) {} n.close(); onClick(); };
      (globalThis.__omniappNotes = globalThis.__omniappNotes || []).push(n);
    } catch (e) {}
  };
  if (Notification.permission === 'granted') show();
  else if (Notification.permission !== 'denied')
    Notification.requestPermission().then(function(p){ if (p === 'granted') show(); }).catch(function(){});
})()""",
    )

private fun jsCancelNotifications(): Unit =
    js("(function(){ (globalThis.__omniappNotes || []).forEach(function(n){ try { n.close(); } catch (e) {} }); globalThis.__omniappNotes = []; })()")

// --- Voice -----------------------------------------------------------------------------------------------------

private val audioScope = CoroutineScope(Dispatchers.Default)

/**
 * PRD §11/§15: a [VoiceCue] plays the same bundled WAV as every other platform; any other utterance — and a cue
 * whose file cannot be read — goes to the browser's speech synthesis. Both queue (FIFO), never overlap. A browser
 * plays nothing before the page's first click or key (its autoplay rule), which a running app is long past.
 */
actual fun speak(utterance: VoiceUtterance) {
    val level = AppVolume.level.toDouble()
    val cue = utterance.cue
    if (cue == null) {
        jsSpeakText(utterance.text, level)
        return
    }
    audioScope.launch {
        val bytes = voiceCueBytes(cue)
        if (bytes != null && bytes.isNotEmpty()) jsQueueVoiceAudio(wavDataUrl(bytes), level)
        else jsSpeakText(utterance.text, level)
    }
}

actual fun stopSpeaking() = jsStopVoice()

private fun jsSpeakText(text: String, volume: Double): Unit =
    js("(function(){ try { var u = new SpeechSynthesisUtterance(text); u.volume = volume; window.speechSynthesis.speak(u); } catch (e) {} })()")

private fun jsQueueVoiceAudio(url: String, volume: Double): Unit =
    js(
        """(function(){
  var g = globalThis.__omniappVoice = globalThis.__omniappVoice || { queue: [], current: null };
  var next = function(){
    var item = g.queue.shift();
    if (!item) { g.current = null; return; }
    try {
      var a = new Audio(item.url); a.volume = item.volume; g.current = a;
      a.onended = next; a.onerror = next;
      var p = a.play(); if (p && p.catch) p.catch(next);
    } catch (e) { next(); }
  };
  g.queue.push({ url: url, volume: volume });
  if (!g.current) next();
})()""",
    )

private fun jsStopVoice(): Unit =
    js(
        """(function(){
  var g = globalThis.__omniappVoice;
  if (g) { g.queue = []; if (g.current) { try { g.current.onended = null; g.current.pause(); } catch (e) {} g.current = null; } }
  try { window.speechSynthesis.cancel(); } catch (e) {}
})()""",
    )

// --- Alarm ring ------------------------------------------------------------------------------------------------

/**
 * PRD §18: the row's sound, built once as a WAV ([AlarmTone.ringWav], already at the app's volume) and played for
 * its length; a newer ring cuts the one still sounding. Vibration where the browser has it (a phone's).
 */
actual fun ringAlarmPlatform(label: String, soundSeconds: Int, sound: AlertSound?, vibrate: Boolean) {
    if (soundSeconds <= 0) return
    if (vibrate) jsVibrate(soundSeconds)
    if (sound == null) return
    audioScope.launch { jsPlayRing(wavDataUrl(AlarmTone.ringWav(sound, soundSeconds))) }
}

private fun jsPlayRing(url: String): Unit =
    js(
        """(function(){
  try {
    if (globalThis.__omniappRing) globalThis.__omniappRing.pause();
    var a = new Audio(url); globalThis.__omniappRing = a;
    var p = a.play(); if (p && p.catch) p.catch(function(){});
  } catch (e) {}
})()""",
    )

private fun jsVibrate(seconds: Int): Unit =
    js(
        """(function(){
  try {
    if (!navigator.vibrate) return;
    var pattern = []; for (var i = 0; i < seconds; i++) { pattern.push(700); pattern.push(300); }
    navigator.vibrate(pattern);
  } catch (e) {}
})()""",
    )

@OptIn(ExperimentalEncodingApi::class)
private fun wavDataUrl(bytes: ByteArray): String = "data:audio/wav;base64," + Base64.encode(bytes)
