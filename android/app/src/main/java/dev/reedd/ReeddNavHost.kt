package dev.reedd

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.reedd.ui.admin.AdminScreen
import dev.reedd.ui.admin.AdminViewModel
import dev.reedd.ui.detail.BookDetailScreen
import dev.reedd.ui.detail.BookDetailViewModel
import dev.reedd.ui.library.LibraryScreen
import dev.reedd.ui.library.LibraryViewModel
import dev.reedd.ui.reader.NotesViewModel
import dev.reedd.ui.reader.ReadAlongViewModel
import dev.reedd.ui.reader.ReaderScreen
import dev.reedd.ui.reader.ReaderState
import dev.reedd.ui.reader.ReaderViewModel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import dev.reedd.ui.settings.BookmarkLabelsScreen
import dev.reedd.ui.settings.BookmarkLabelsViewModel
import dev.reedd.ui.settings.SettingsScreen
import dev.reedd.ui.settings.SettingsViewModel
import dev.reedd.ui.voices.VoicesScreen
import dev.reedd.ui.voices.VoicesViewModel
import kotlinx.serialization.Serializable

@Serializable
object LibraryRoute

@Serializable
object SettingsRoute

@Serializable
object AdminRoute

@Serializable
object VoicesRoute

@Serializable
object BookmarkLabelsRoute

@Serializable
data class DetailRoute(val bookId: String)

/** @param autoPlay Start audio immediately once the book opens -- the
 *  library's own play button, not every way of reaching the reader (the
 *  detail screen's "Read" stays a plain open). */
@Serializable
data class ReaderRoute(val bookId: String, val autoPlay: Boolean = false)

/**
 * The whole navigation graph. Four destinations, one activity.
 *
 * [LibraryViewModel] is deliberately scoped to the library destination rather than
 * created per screen: it owns the foreground poll loop, and a second copy would
 * mean two loops hitting the server.
 */
@Composable
fun ReeddNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val container = (context.applicationContext as ReeddApp).container

    NavHost(navController = navController, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            val viewModel: LibraryViewModel =
                viewModel(factory = LibraryViewModel.factory(container, context))
            LibraryScreen(
                viewModel = viewModel,
                onOpenBook = { id, autoPlay -> navController.navigate(ReaderRoute(id, autoPlay = autoPlay)) },
                onOpenDetail = { navController.navigate(DetailRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }

        composable<DetailRoute> { entry ->
            val bookId = entry.toRoute<DetailRoute>().bookId
            // One ViewModel, which owns both the state and the actions. Constructing a
            // LibraryViewModel here as well used to start a second polling loop.
            val detailViewModel: BookDetailViewModel =
                viewModel(factory = BookDetailViewModel.factory(container, context, bookId))

            BookDetailScreen(
                viewModel = detailViewModel,
                onBack = { navController.popBackStack() },
                onRead = { navController.navigate(ReaderRoute(it)) },
                onDeleted = { navController.popBackStack() },
            )
        }

        composable<ReaderRoute> { entry ->
            val route = entry.toRoute<ReaderRoute>()
            val viewModel: ReaderViewModel =
                viewModel(factory = ReaderViewModel.factory(container, route.bookId))
            // Separate ViewModel: one owns the open publication, the other the
            // audio. They have different lifetimes and different reasons to change.
            val readAlongViewModel: ReadAlongViewModel =
                viewModel(
                    factory = ReadAlongViewModel.factory(
                        container, route.bookId, route.autoPlay,
                        // Only ever awaited for a canReadLive book -- see
                        // LiveChunkSource's own doc for why this stays a plain
                        // suspend function of hrefs rather than handing the whole
                        // Publication across ViewModels. Suspends rather than
                        // reading viewModel.state.value directly: this factory
                        // runs before ReaderViewModel.open() (its own separate
                        // async load) has necessarily finished.
                        readingOrderHrefs = {
                            viewModel.state.filterIsInstance<ReaderState.Ready>().first()
                                .publication.readingOrder.map { it.url().toString() }
                        },
                    )
                )
            val notesViewModel: NotesViewModel =
                viewModel(factory = NotesViewModel.factory(container, route.bookId))
            val guideViewModel: dev.reedd.ui.reader.GuideViewModel =
                viewModel(factory = dev.reedd.ui.reader.GuideViewModel.factory(container, route.bookId))
            ReaderScreen(
                viewModel = viewModel,
                readAlongViewModel = readAlongViewModel,
                notesViewModel = notesViewModel,
                guideViewModel = guideViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable<SettingsRoute> {
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenAdmin = { navController.navigate(AdminRoute) },
                onOpenVoices = { navController.navigate(VoicesRoute) },
                onOpenBookmarkLabels = { navController.navigate(BookmarkLabelsRoute) },
            )
        }

        composable<BookmarkLabelsRoute> {
            val viewModel: BookmarkLabelsViewModel = viewModel(factory = BookmarkLabelsViewModel.factory(container))
            BookmarkLabelsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable<AdminRoute> {
            val viewModel: AdminViewModel = viewModel(factory = AdminViewModel.factory(container))
            AdminScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }

        composable<VoicesRoute> {
            val viewModel: VoicesViewModel = viewModel(factory = VoicesViewModel.factory(container, context))
            VoicesScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
