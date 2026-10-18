package com.oamsapps.productsnap.telemetry

import android.app.Application
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.oamsapps.productsnap.BuildConfig

/**
 * Crash reports and a handful of usage events, through Firebase. A no-op when the build has no Firebase config
 * (see app/build.gradle.kts), so nothing here may be relied on for app behaviour.
 *
 * Events carry counts and choices only (marketplace, background type, verdicts), never photos, file names or
 * anything typed by the user.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    /** One line for the debug screen: is reporting on, and to which Firebase project. */
    var status: String = "Not started"
        private set

    fun init(app: Application) {
        val apps = FirebaseApp.getApps(app)
        if (apps.isEmpty()) {
            status = "Off: this build has no Firebase config"
            Log.i(TAG, status)
            return
        }
        runCatching {
            analytics = FirebaseAnalytics.getInstance(app)
            crashlytics = FirebaseCrashlytics.getInstance().apply {
                setCustomKey("build", BuildConfig.BUILD_ID)
                setCustomKey("debug", BuildConfig.DEBUG)
            }
            val o = apps.first().options
            status = "On: project ${o.projectId ?: "?"}, app ${o.applicationId}, collection " +
                (if (crashlytics?.isCrashlyticsCollectionEnabled == true) "enabled" else "disabled")
            Log.i(TAG, status)
        }.onFailure {
            status = "Off: Firebase didn't start (${it.javaClass.simpleName}: ${it.message})"
            Log.w(TAG, status, it)
        }
    }

    /**
     * Debug check that doesn't crash the app: records a handled error and asks Crashlytics to upload now
     * (it shows under Non-fatals in the console). Returns false when reporting is off.
     */
    fun sendTestReport(): Boolean {
        val c = crashlytics ?: return false
        c.log("Test report from build ${BuildConfig.BUILD_ID}")
        c.recordException(RuntimeException("Test non-fatal from build ${BuildConfig.BUILD_ID}"))
        c.sendUnsentReports()
        return true
    }

    /** [name] and keys in snake_case, at most 40 characters (Analytics limits). */
    fun event(name: String, vararg params: Pair<String, Any?>) {
        val a = analytics ?: return
        val b = Bundle()
        for ((k, v) in params) when (v) {
            null -> Unit
            is Int -> b.putLong(k, v.toLong())
            is Long -> b.putLong(k, v)
            is Float -> b.putDouble(k, v.toDouble())
            is Double -> b.putDouble(k, v)
            is Boolean -> b.putString(k, v.toString())
            else -> b.putString(k, v.toString().take(100))
        }
        runCatching { a.logEvent(name, b) }
    }

    /** A handled error worth seeing in Crashlytics (a photo that failed, not a crash). */
    fun nonFatal(t: Throwable, where: String) {
        val c = crashlytics ?: return
        runCatching {
            c.log(where)
            c.recordException(t)
        }
    }
}
