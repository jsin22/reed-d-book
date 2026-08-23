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
import dev.reedd.ui.reader.ReadAlongViewModel
import dev.reedd.ui.reader.ReaderScreen
import dev.reedd.ui.reader.ReaderViewModel
import dev.reedd.ui.settings.SettingsScreen
import dev.reedd.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable
object LibraryRoute

@Serializable
object SettingsRoute

@Serializable
object AdminRoute

@Serializable
data class DetailRoute(val bookId: String)

@Serializable
data class ReaderRoute(val bookId: String)

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
                onOpenBook = { navController.navigate(ReaderRoute(it)) },
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
            val bookId = entry.toRoute<ReaderRoute>().bookId
            val viewModel: ReaderViewModel =
                viewModel(factory = ReaderViewModel.factory(container, bookId))
            // Separate ViewModel: one owns the open publication, the other the
            // audio. They have different lifetimes and different reasons to change.
            val readAlongViewModel: ReadAlongViewModel =
                viewModel(factory = ReadAlongViewModel.factory(container, bookId))
            ReaderScreen(
                viewModel = viewModel,
                readAlongViewModel = readAlongViewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable<SettingsRoute> {
            val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenAdmin = { navController.navigate(AdminRoute) },
            )
        }

        composable<AdminRoute> {
            val viewModel: AdminViewModel = viewModel(factory = AdminViewModel.factory(container))
            AdminScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
