package app.cloudnotes.pro

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * #1949 activity lifecycle trace (tag Notes1949): every create/resume/stop/destroy/finish of a Notes activity with its intent, and the call
 * stack of every startActivity / navigateUpTo / finish made by one. The grant walk reads it to prove which caller takes the reader down.
 */
object LifecycleProbe : Application.ActivityLifecycleCallbacks {
    const val TAG = "Notes1949"

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    fun call(who: Activity, what: String, intent: Intent? = null) {
        Log.w(TAG, "${who.javaClass.simpleName} $what ${intent?.toString() ?: ""}", Throwable("caller"))
    }

    private fun log(a: Activity, ev: String) { Log.i(TAG, "${a.javaClass.simpleName}@${System.identityHashCode(a)} $ev task=${a.taskId} finishing=${a.isFinishing} intent=${a.intent}") }
    override fun onActivityCreated(a: Activity, s: Bundle?) = log(a, "created saved=${s != null}")
    override fun onActivityStarted(a: Activity) = log(a, "started")
    override fun onActivityResumed(a: Activity) = log(a, "resumed")
    override fun onActivityPaused(a: Activity) = log(a, "paused")
    override fun onActivityStopped(a: Activity) = log(a, "stopped")
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) = log(a, "destroyed")
}
