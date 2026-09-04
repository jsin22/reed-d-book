package dev.reedd

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import dev.reedd.di.AppContainer
import dev.reedd.diagnostics.Breadcrumbs
import dev.reedd.diagnostics.CrashReporter

/**
 * Owns the dependency graph.
 *
 * Deliberately hand-rolled rather than Hilt: there are about a dozen objects to
 * wire, all of them singletons, and a KSP-based DI framework would add another
 * version to keep in lockstep with Kotlin for no benefit at this size.
 */
class ReeddApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // A fresh process gets a fresh trail -- see Breadcrumbs' own doc on
        // why mixing one process' breadcrumbs into a report written by a
        // later one would be actively misleading, not just stale.
        Breadcrumbs.clear()
        // First, before anything else can fail: this is what makes a crash during
        // container construction legible rather than a silent disappearance.
        CrashReporter.install(this)
        // Also before AppContainer: if construction itself is what crashed last
        // time, the normal CrashLog.start() below never runs, and a bug that
        // reproduces on every launch would otherwise never get reported at all.
        CrashReporter.sendPendingEarly(this)
        // A single-Activity app (MainActivity only), so a start/stop count of
        // one Activity is exactly "is the app on screen" -- cheaper than
        // pulling in the separate androidx.lifecycle:lifecycle-process
        // artifact for a ProcessLifecycleOwner this app has no other use for.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = Breadcrumbs.leave("app foregrounded")
            override fun onActivityStopped(activity: Activity) = Breadcrumbs.leave("app backgrounded")
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        // Debug builds only: the reader's layout bugs (BUGS.md BUG-12) turned out
        // to depend on exactly what CSS a specific book ships, which static
        // analysis can diagnose but not fully verify without a device. With this
        // on, `chrome://inspect` on a computer the phone is plugged into (or on
        // the same network with `adb forward`) opens DevTools on the reader's
        // actual live WebView -- the DOM, computed styles and all -- instead of
        // guessing from decompiled library source. Must be set before any WebView
        // is created, which is why this is here and not e.g. lazily in
        // AppContainer.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        container = AppContainer(this)
        container.crashLog.start(container.appScope)
    }
}
