package com.diegonmarcos.superapp.headless

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.webkit.WebView
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * #1498 DevTools/CDP forward: a loopback TCP port relayed to this process's `webview_devtools_remote_<pid>` abstract socket (an app may
 * connect to its own DevTools socket, so no privileged shell is needed on the phone; the privileged shell / `adb forward` form is in
 * [CdpPolicy] for a host that wants the port on its own side). The FIRST request of each connection must carry `Authorization: Bearer <token>`
 * (Playwright `connectOverCDP(url, {headers})`); the header is checked, then the bytes are piped untouched. Default off; [stop] closes it.
 */
class CdpRelay(private val gate: HeadlessTokenGate, private val socketName: String) {
    private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0

    fun start(preferred: Int) {
        val s = ServerSocket(preferred, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        Thread({ while (!s.isClosed) { val c = runCatching { s.accept() }.getOrNull() ?: break; Thread({ relay(c) }, "cdp-relay").apply { isDaemon = true }.start() } }, "cdp-accept").apply { isDaemon = true }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun relay(c: Socket) {
        val dev = LocalSocket()
        try {
            val cin = c.getInputStream(); val cout = c.getOutputStream()
            // read the request head (up to the blank line), check the bearer, then forward it as is
            val head = java.io.ByteArrayOutputStream(); var last4 = 0
            while (true) { val b = cin.read(); if (b < 0) return; head.write(b); last4 = (last4 shl 8) or b; if (last4 == 0x0d0a0d0a || head.size() > 16384) break }
            val text = head.toString("ISO-8859-1")
            val auth = Regex("(?im)^authorization:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)
            if (gate.check(auth) != null) { cout.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); cout.flush(); return }
            dev.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            dev.outputStream.write(head.toByteArray()); dev.outputStream.flush()
            val t = Thread({ pipe(dev.inputStream, cout) }, "cdp-down").apply { isDaemon = true }; t.start()
            pipe(cin, dev.outputStream)
        } catch (e: Exception) {
        } finally { runCatching { dev.close() }; runCatching { c.close() } }
    }

    private fun pipe(i: InputStream, o: OutputStream) { val b = ByteArray(8192); while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); o.flush() } }

    companion object {
        fun enableDebugging() { android.os.Handler(android.os.Looper.getMainLooper()).post { WebView.setWebContentsDebuggingEnabled(true) } }
    }
}
