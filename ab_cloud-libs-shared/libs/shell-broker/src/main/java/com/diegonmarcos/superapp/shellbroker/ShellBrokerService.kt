package com.diegonmarcos.superapp.shellbroker

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire

/**
 * #1510 The bound-service door (Messenger): what = [WHAT_EXEC] with data {cmd, timeoutMs, maxBytes}, answered on `replyTo` with
 * data {json}. The caller is Message.sendingUid, resolved to a package; the service is exported behind the signature
 * permission. It exists only while bound: never started, no START_STICKY, no foreground, no boot hook. When the last client
 * unbinds it drops the idle worker threads and the system destroys it.
 */
class ShellBrokerService : Service() {
    private var thread: HandlerThread? = null
    private var messenger: Messenger? = null

    override fun onBind(intent: Intent?): IBinder? {
        val t = HandlerThread("shell-broker-bind").also { it.start(); thread = it }
        messenger = Messenger(object : Handler(t.looper) {
            override fun handleMessage(msg: Message) {
                val reply = msg.replyTo ?: return
                val uid = msg.sendingUid
                val app = applicationContext
                val json = when {
                    msg.what != WHAT_EXEC -> ShellWire.encode(ShellResult.Failed("unknown message"))
                    !CallerId.permitted(app, uid) -> ShellWire.encode(ShellResult.Denied("signature permission missing"))
                    else -> {
                        val caller = CallerId.of(app, uid, null)
                        val d = msg.data
                        if (caller == null) ShellWire.encode(ShellResult.Denied("unknown caller"))
                        else ShellWire.encode(ShellBroker.exec(app, caller, d.getString("cmd").orEmpty(),
                            d.getLong(ShellWire.K_TIMEOUT, ShellWire.DEFAULT_TIMEOUT_MS), d.getInt(ShellWire.K_MAX_BYTES, ShellWire.EXEC_MAX_BYTES)))
                    }
                }
                runCatching { reply.send(Message.obtain(null, WHAT_EXEC).apply { data = Bundle().apply { putString(ShellWire.K_JSON, json) } }) }
            }
        })
        return messenger?.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ShellBroker.reapIfIdle()
        thread?.quitSafely(); thread = null; messenger = null
        return false
    }

    companion object { const val WHAT_EXEC = 1 }
}
