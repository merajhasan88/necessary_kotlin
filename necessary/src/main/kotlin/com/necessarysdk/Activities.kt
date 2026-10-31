package com.necessarysdk

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * The app's activities, as the SDK needs them: the one the person sees, for
 * the step-up prompt, the location prompt, screen protection and the
 * screen_capture measurement; and each new window, which screen protection
 * covers while it is held. The SDK's own prompt activity is left out.
 */
internal object Activities : Application.ActivityLifecycleCallbacks {
    @Volatile var visible: Activity? = null
        private set

    /** Called on the main thread for each activity created while the app runs. */
    @Volatile var onNewWindow: ((Activity) -> Unit)? = null

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(this)
            installed = true
        }
    }

    private fun ours(activity: Activity) = activity is PromptActivity

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (!ours(activity)) onNewWindow?.invoke(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (!ours(activity)) visible = activity
    }

    override fun onActivityResumed(activity: Activity) {
        if (!ours(activity)) visible = activity
    }

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        if (visible === activity) visible = null
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {
        if (visible === activity) visible = null
    }
}

/**
 * Starts with the app, before its first activity, so that the SDK knows
 * which activity the person sees whenever the app makes its DeviceTrust.
 * It serves no data.
 */
class NecessaryInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { Activities.install(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
