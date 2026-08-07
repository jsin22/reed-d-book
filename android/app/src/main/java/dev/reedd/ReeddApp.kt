package dev.reedd

import android.app.Application
import dev.reedd.di.AppContainer

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
        container = AppContainer(this)
    }
}
