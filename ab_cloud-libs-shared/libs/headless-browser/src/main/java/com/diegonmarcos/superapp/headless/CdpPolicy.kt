package com.diegonmarcos.superapp.headless

/**
 * #1498 opt-in DevTools/CDP mode for Playwright/Puppeteer `connectOverCDP`. DEFAULT OFF, token-gated and self-expiring:
 * it turns on WebView.setWebContentsDebuggingEnabled and forwards the `webview_devtools_remote_<pid>` abstract socket to a TCP port
 * through the privileged shell (Shizuku / wireless ADB, libs:shizuku-adb-debug-tools), then switches itself off after [minutes].
 */
object CdpPolicy {
    const val KEY_ON = "headless_cdp"
    const val KEY_MINUTES = "headless_cdp_minutes"
    const val KEY_PORT = "headless_cdp_port"
    const val MINUTES_DEFAULT = 10
    const val PORT_DEFAULT = 9222

    fun clampMinutes(m: Int?): Int = (m ?: MINUTES_DEFAULT).coerceIn(1, 60)
    fun clampPort(p: Int?): Int = (p ?: PORT_DEFAULT).coerceIn(1024, 65535)

    /** The abstract socket of the app process's DevTools server. */
    fun socketName(pid: Int) = "webview_devtools_remote_$pid"

    /** The shell command that forwards [port] to the DevTools socket of [pid]; the shell binds loopback, the mesh reaches it through the host's own tunnel. */
    fun forwardCommand(pid: Int, port: Int) = "adb forward tcp:${clampPort(port)} localabstract:${socketName(pid)}"
    fun unforwardCommand(port: Int) = "adb forward --remove tcp:${clampPort(port)}"

    /** Only the device-local `forward` / `forward --remove` of this exact shape is ever sent to the shell. */
    fun allowedCommand(cmd: String): Boolean = Regex("^adb forward (tcp:\\d{4,5} localabstract:webview_devtools_remote_\\d+|--remove tcp:\\d{4,5})$").matches(cmd)

    fun expired(now: Long, armedAt: Long, minutes: Int): Boolean = now - armedAt >= clampMinutes(minutes) * 60_000L

    /** The token gate applies: CDP may be armed only for a caller that presented the loopback token. */
    fun mayArm(tokenOk: Boolean, setting: Boolean): Boolean = tokenOk && setting
}
