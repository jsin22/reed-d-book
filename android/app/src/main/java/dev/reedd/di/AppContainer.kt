package dev.reedd.di

import android.content.Context
import dev.reedd.data.BookRepository
import dev.reedd.data.db.ReeddDatabase
import dev.reedd.data.db.SyncDao
import dev.reedd.data.download.ResumableDownloader
import dev.reedd.data.local.BookFiles
import dev.reedd.data.local.EpubImporter
import dev.reedd.data.readium.ReadiumComponents
import dev.reedd.data.remote.ApiProvider
import dev.reedd.data.settings.SettingsStore
import dev.reedd.domain.ConversionWatcher
import dev.reedd.domain.ReadAlongAligner
import dev.reedd.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The dependency graph, built once in [dev.reedd.ReeddApp].
 *
 * Hand-rolled rather than Hilt: there are a dozen objects here, all singletons,
 * and a KSP-based DI framework would add another version to keep in lockstep with
 * Kotlin for no benefit at this size. Workers reach it through the Application,
 * which is the only global anything needs.
 *
 * Everything is `by lazy`, so opening the app does not build an HTTP stack or
 * touch the database before something asks for one.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    /** Outlives every screen; used for work that must not die with a ViewModel. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsStore by lazy { SettingsStore(appContext, appScope) }

    val database: ReeddDatabase by lazy { ReeddDatabase.create(appContext) }

    val syncStore: SyncDao by lazy { database.sync() }

    val files: BookFiles by lazy { BookFiles(appContext) }

    /**
     * Reads the address and token straight out of the settings snapshot, so
     * changing the server in Settings takes effect without rebuilding the graph.
     */
    val api: ApiProvider by lazy {
        ApiProvider(
            baseUrl = { settings.snapshot.baseUrl },
            token = { settings.snapshot.token },
            debugLogging = dev.reedd.BuildConfig.DEBUG,
        )
    }

    /** Shares the API's OkHttpClient: one connection pool to the same host. */
    val downloader: ResumableDownloader by lazy { ResumableDownloader(api.okHttp) }

    val readium: ReadiumComponents by lazy { ReadiumComponents(appContext) }

    val importer: EpubImporter by lazy { EpubImporter(appContext, files, readium) }

    val notifications: Notifications by lazy {
        Notifications(appContext).also { it.ensureChannels() }
    }

    val repository: BookRepository by lazy {
        BookRepository(database.books(), syncStore, api)
    }

    val watcher: ConversionWatcher by lazy {
        ConversionWatcher(appContext, repository, api, notifications)
    }

    val readAlongAligner: ReadAlongAligner by lazy { ReadAlongAligner(syncStore) }
}
