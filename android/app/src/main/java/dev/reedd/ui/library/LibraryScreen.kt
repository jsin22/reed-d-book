package dev.reedd.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.domain.AuthStatus
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val books by viewModel.visibleBooks.collectAsStateWithLifecycle()
    val libraryView by viewModel.libraryView.collectAsStateWithLifecycle()
    val availableCategories by viewModel.availableCategories.collectAsStateWithLifecycle()
    val availableGenres by viewModel.availableGenres.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val authStatus by viewModel.authStatus.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val isAdmin by viewModel.isAdmin.collectAsStateWithLifecycle()
    // player.state tracks the book actually loaded, which may not be in the
    // current sort/filter view -- read from the full library so the mini-player
    // never disappears just because a filter is narrowed.
    val allBooks by viewModel.books.collectAsStateWithLifecycle()
    val nowPlaying = allBooks.find { it.id == playerState.bookId }
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
    var showSortFilter by remember { mutableStateOf(false) }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // OpenDocument rather than GetContent: it gives a stable, readable URI that
    // survives long enough to copy the file out.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            showImport = true
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(
                        onClick = {
                            // Guarded: launch() throws ActivityNotFoundException if nothing
                            // on the device handles OpenDocument for these types, and an
                            // uncaught throw from a click handler takes the app down.
                            runCatching {
                                picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                            }.onFailure { viewModel.reportProblem("no file picker available on this device") }
                        },
                        enabled = !importing,
                    ) {
                        if (importing) CircularProgressIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Filled.Add, contentDescription = "Add book")
                    }
                    IconButton(onClick = { showSortFilter = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Sort & filter")
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp))
                        else Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Playback outlives the reader on purpose (PlaybackService keeps going
            // in the background) -- this is what makes it visible again once its
            // book is no longer on screen, and gives a way back into it.
            nowPlaying?.let { book ->
                NowPlayingBar(
                    book = book,
                    isPlaying = playerState.isPlaying,
                    onClick = { onOpenBook(book.id) },
                    onTogglePlayPause = viewModel::togglePlayPause,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AuthStatusBanner(authStatus, onOpenSettings)
            if (allBooks.isEmpty()) {
                EmptyLibrary(Modifier.fillMaxSize())
            } else if (books.isEmpty()) {
                // Distinct from EmptyLibrary: there are books, just none that
                // match the current filter -- clearing it, not adding a book,
                // is the fix, so this offers that instead of the import pitch.
                NoFilterMatches(Modifier.fillMaxSize(), onClearFilters = viewModel::clearFilters)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            nowPlaying = book.id == playerState.bookId,
                            isPlaying = playerState.isPlaying,
                            showDetails = isAdmin,
                            onClick = {
                                if (book.isPlayable) onOpenBook(book.id)
                                // A failure now retries right from the card (see
                                // onRetryConversion/onDownload below), so a
                                // non-admin not being able to reach Detail no
                                // longer loses them any action -- Cancel, for a
                                // still-in-flight upload/conversion, is the one
                                // Detail-only action left unreplaced.
                                else if (isAdmin) onOpenDetail(book.id)
                            },
                            onLongClick = if (isAdmin) ({ onOpenDetail(book.id) }) else null,
                            onRead = { onOpenBook(book.id) },
                            onDownload = { viewModel.downloadBook(book.id) },
                            onDeleteDownloaded = { viewModel.deleteDownloadedContent(book.id) },
                            onRetryConversion = { viewModel.retryConversion(book.id) },
                            onCancel = { viewModel.cancelConversion(book.id) },
                        )
                    }
                }
            }
        }
    }

    // A crash from the previous run, readable without adb. Also posted to the
    // server by CrashLog, but this works with no server at all.
    val crashReport by viewModel.crashReport.collectAsStateWithLifecycle()
    crashReport?.let { report ->
        CrashReportDialog(report = report, onDismiss = viewModel::dismissCrashReport)
    }

    if (showSortFilter) {
        LibrarySortFilterSheet(
            view = libraryView,
            categories = availableCategories,
            genres = availableGenres,
            onDismiss = { showSortFilter = false },
            onSortChange = viewModel::setSort,
            onCategoryChange = viewModel::setFilterCategory,
            onGenresChange = viewModel::setFilterGenres,
            onClearFilters = viewModel::clearFilters,
        )
    }

    if (showImport) {
        ImportSheet(
            viewModel = viewModel,
            onDismiss = {
                showImport = false
                pickedUri = null
            },
            onConfirm = { voice, speed, engine ->
                pickedUri?.let { viewModel.importAndUpload(it, voice, speed, engine) }
                showImport = false
                pickedUri = null
            },
        )
    }
}

/**
 * Surfaces a missing/invalid token or an unreachable server right on the
 * library, instead of an empty list that looks identical to "no books yet"
 * -- see BUGS.md BUG-23, where exactly that ambiguity made a bad token very
 * hard to diagnose from the user's side. Silent for [AuthStatus.Unknown]
 * (nothing checked yet) and [AuthStatus.Ok].
 */
@Composable
private fun AuthStatusBanner(status: AuthStatus, onOpenSettings: () -> Unit) {
    val message = when (status) {
        is AuthStatus.NeedsToken -> "No valid API token. Add one from your invite in Settings."
        is AuthStatus.Unreachable -> status.reason
        AuthStatus.Unknown, AuthStatus.Ok -> null
    } ?: return

    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (status is AuthStatus.NeedsToken) {
                androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}

@Composable
private fun NoFilterMatches(modifier: Modifier, onClearFilters: () -> Unit) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.FilterList,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "No books match this filter",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        androidx.compose.material3.TextButton(onClick = onClearFilters, modifier = Modifier.padding(top = 8.dp)) {
            Text("Clear filter")
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "No books yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Add an .epub and it will be sent to your conversion server, then come back as an audiobook you can read along with.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Whether there is a local audiobook/sync file to offer clearing -- includes a
 *  partial/interrupted download, not just a finished one, so the trash icon can
 *  also recover a stuck [BookStage.FAILED] or [BookStage.DOWNLOADING] card. */
private fun BookEntity.hasLocalDownload(): Boolean =
    audiobookPath != null || syncPath != null || downloadedBytes > 0

/** While a job is still in flight server-side (or the upload to start one is),
 *  matching exactly the stages [ProgressLine] draws a bar for -- the point
 *  where "cancel" means something to terminate, as opposed to a download in
 *  progress (see BookDetailViewModel.cancel, which this mirrors and which
 *  likewise never offers to cancel a download, only the job itself). */
private fun BookEntity.isCancellable(): Boolean =
    stage() == BookStage.QUEUED || stage() == BookStage.CONVERTING || stage() == BookStage.UPLOADING

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    nowPlaying: Boolean,
    isPlaying: Boolean,
    /** Whether this card may navigate to the detail screen at all -- non-admins
     *  cannot, so both the long-press menu and the FAILED/LOST shortcut below
     *  are omitted entirely for them rather than shown and then rejected. */
    showDetails: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownloaded: () -> Unit,
    onRetryConversion: () -> Unit,
    onCancel: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Card(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick?.let { { showMenu = true } },
                )
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(book, Modifier.size(width = 56.dp, height = 80.dp))
                Column(
                    Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                ) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Blank once a book has never been sent for conversion (no
                    // engine/voice chosen yet), rather than showing a stray "·".
                    val engineVoice = listOfNotNull(book.engine, book.voice).joinToString(" · ")
                    if (engineVoice.isNotEmpty()) {
                        Text(
                            engineVoice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StageChip(book, nowPlaying = nowPlaying, isPlaying = isPlaying)
                        if (showDetails && onLongClick != null &&
                            (book.stage() == BookStage.FAILED || book.stage() == BookStage.LOST)
                        ) {
                            Text(
                                "Details",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(onClick = onLongClick),
                            )
                        }
                    }
                    // The specific reason, surfaced right here: this is what lets
                    // the card be self-sufficient (retry included) without a trip
                    // to Detail, which non-admins cannot reach at all any more.
                    failureReason(book)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    ProgressLine(book)
                }
                CardAction(book, onRead = onRead, onDownload = onDownload, onRetryConversion = onRetryConversion)
                if (book.isCancellable()) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                }
                if (book.hasLocalDownload()) {
                    IconButton(onClick = onDeleteDownloaded) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete downloaded content",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        // The only way to reach the detail/metadata screen for a book that is
        // already playing -- tapping it opens the reader instead -- so this is
        // the one entry point that works for every stage, not just failures.
        // Admin-only: see [showDetails] and [onLongClick].
        if (onLongClick != null) {
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Details") },
                    onClick = {
                        showMenu = false
                        onLongClick()
                    },
                )
            }
        }
    }
}

/**
 * The card's trailing button: Download once a conversion is on the server
 * waiting to be fetched, Read once it is on the device. Nested inside the
 * card's own [combinedClickable] -- Compose gives a clickable child first
 * crack at a tap, so pressing this never also triggers the card's onClick.
 */
@Composable
private fun CardAction(
    book: BookEntity,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onRetryConversion: () -> Unit,
) {
    when {
        book.stage() == BookStage.AVAILABLE -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = "Download")
        }
        book.stage() == BookStage.READY -> IconButton(onClick = onRead) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Read along")
        }
        // Checked before the generic FAILED/LOST branch below: book.stage()
        // already collapses every failure cause into BookStage.FAILED (see
        // BookEntity.stage()), so downloadState is what actually tells a
        // failed download apart from a failed upload/conversion here.
        book.downloadState == DownloadState.FAILED -> IconButton(onClick = onDownload) {
            Icon(Icons.Filled.Download, contentDescription = "Retry download")
        }
        // LOCAL alongside FAILED/LOST: a book cancelled mid-upload or
        // mid-conversion (LibraryViewModel.cancelConversion) lands back on
        // LOCAL, same as a book that was never sent at all -- either way,
        // (re)starting the upload is the right action.
        book.stage() == BookStage.FAILED || book.stage() == BookStage.LOST || book.stage() == BookStage.LOCAL ->
            IconButton(onClick = onRetryConversion) {
                Icon(Icons.Filled.Refresh, contentDescription = "Retry")
            }
        else -> Unit
    }
}

/**
 * The specific reason a card is showing a failure, for the error line next to
 * [StageChip] -- null for everything else. [BookEntity.stage] only ever says
 * *that* something is FAILED/LOST, not which of upload, conversion or
 * download it was, so this reads the underlying fields directly the same way
 * [CardAction] does to pick the right retry action.
 */
private fun failureReason(book: BookEntity): String? = when {
    book.stage() == BookStage.LOST ->
        "The server no longer has this job. Retry will send it again."
    book.downloadState == DownloadState.FAILED ->
        book.downloadError ?: "The download failed."
    book.stage() == BookStage.FAILED ->
        book.jobError ?: book.uploadError ?: "Something went wrong."
    else -> null
}

@Composable
private fun Cover(book: BookEntity, modifier: Modifier) {
    val cover = book.coverPath?.let(::File)?.takeIf { it.isFile }
    if (cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
        )
    } else {
        Box(
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StageChip(book: BookEntity, nowPlaying: Boolean, isPlaying: Boolean) {
    // Takes over the ready-to-read chip's slot rather than adding a second badge:
    // once a book is the one loaded in the player, "ready to read along" is no
    // longer the most useful thing this card can say about it.
    val downloadEta = rememberDownloadEta(book)
    val (label, tint) = if (nowPlaying) {
        (if (isPlaying) "Playing" else "Paused") to MaterialTheme.colorScheme.primary
    } else when (book.stage()) {
        BookStage.LOCAL -> "On device only" to MaterialTheme.colorScheme.onSurfaceVariant
        BookStage.UPLOADING -> "Uploading" to MaterialTheme.colorScheme.primary
        BookStage.QUEUED -> "Queued on server" to MaterialTheme.colorScheme.primary
        BookStage.CONVERTING -> buildString {
            append("Converting ${book.jobProgress}%")
            book.jobEta?.let { append(" · ${it.humanEta()}") }
        } to MaterialTheme.colorScheme.primary
        BookStage.AVAILABLE -> "Ready to download" to MaterialTheme.colorScheme.primary
        BookStage.DOWNLOADING -> buildString {
            append("Downloading")
            if (book.downloadTotalBytes > 0) {
                val percent = (book.downloadedBytes * 100L / book.downloadTotalBytes).coerceIn(0L, 100L)
                append(" $percent%")
            }
            downloadEta?.let { append(" · $it") }
        } to MaterialTheme.colorScheme.primary
        BookStage.READY -> "Ready to read along" to MaterialTheme.colorScheme.primary
        BookStage.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        BookStage.LOST -> "Job lost on server" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        enabled = false,
        leadingIcon = if (nowPlaying) {
            {
                Icon(
                    if (isPlaying) Icons.Filled.VolumeUp else Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else null,
        label = { Text(label, style = MaterialTheme.typography.labelSmall, color = tint) },
    )
}

@Composable
private fun ProgressLine(book: BookEntity) {
    val fraction: Float? = when (book.stage()) {
        BookStage.CONVERTING -> book.jobProgress / 100f
        BookStage.UPLOADING -> if (book.sizeBytes > 0) book.uploadedBytes.toFloat() / book.sizeBytes else null
        BookStage.DOWNLOADING ->
            if (book.downloadTotalBytes > 0) book.downloadedBytes.toFloat() / book.downloadTotalBytes else null
        else -> null
    }
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(3.dp),
        )
    }
}

/**
 * A persistent mini-player, so leaving the reader (playback keeps going in the
 * background on purpose) does not mean losing track of what is playing or how
 * to get back to it.
 *
 * `navigationBarsPadding()` is required here, not optional: Material3's
 * `Scaffold` insets its content slot but places `bottomBar` flush against the
 * bottom edge and leaves the bar to inset itself (BUGS.md, BUG-1 -- the same fix
 * `ReadAlongBar` needed).
 */
@Composable
private fun NowPlayingBar(
    book: BookEntity,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(book, Modifier.size(width = 40.dp, height = 40.dp))
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isPlaying) "Playing" else "Paused",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
        }
    }
}

/**
 * Shortens the server's `"00d 00h 00m 11s"` to something a phone screen can hold.
 *
 * Formatted server-side by audiblez, so it is reformatted here rather than parsed
 * into a duration: leading zero units are simply dropped.
 */
fun String.humanEta(): String =
    split(' ')
        .dropWhile { it.length > 1 && it.dropLast(1).toIntOrNull() == 0 }
        .take(2)
        .joinToString(" ")
        .ifBlank { this }

/** Mutated in place, not a [androidx.compose.runtime.MutableState]: the recomposition
 *  that should show a new ETA already happens because [BookEntity.downloadedBytes]
 *  itself changed, so this only needs to survive across those recompositions, not
 *  trigger one of its own. */
private class DownloadRateBaseline(var timeMs: Long, var bytes: Long)

/**
 * A short "Xm Ys"-style estimate for how much longer a download has left.
 *
 * Unlike [BookEntity.jobEta] (a conversion's ETA, computed server-side by
 * audiblez and just reformatted by [humanEta]), a raw file download has no
 * server-side ETA to report -- [book]'s [BookEntity.downloadedBytes] only says
 * how far it's gotten. This estimates a rate from recent progress instead,
 * rebasing every few seconds so it tracks the current network conditions
 * rather than averaging over the whole download (which would stay skewed by
 * a slow start long after the download has sped up).
 */
@Composable
private fun rememberDownloadEta(book: BookEntity): String? {
    if (book.stage() != BookStage.DOWNLOADING || book.downloadTotalBytes <= 0) return null
    val baseline = remember(book.id) { DownloadRateBaseline(System.currentTimeMillis(), book.downloadedBytes) }
    if (book.downloadedBytes < baseline.bytes) {
        // A new download started from scratch in this same card (e.g. deleted
        // and re-downloaded) -- the old rate no longer means anything.
        baseline.timeMs = System.currentTimeMillis()
        baseline.bytes = book.downloadedBytes
    }
    val now = System.currentTimeMillis()
    val elapsedS = (now - baseline.timeMs) / 1000.0
    val deltaBytes = book.downloadedBytes - baseline.bytes
    val eta = if (elapsedS >= 1.0 && deltaBytes > 0) {
        val bytesPerSecond = deltaBytes / elapsedS
        val remainingBytes = book.downloadTotalBytes - book.downloadedBytes
        formatEtaSeconds((remainingBytes / bytesPerSecond).toLong()).takeIf { remainingBytes > 0 }
    } else null
    if (elapsedS >= 3.0) {
        baseline.timeMs = now
        baseline.bytes = book.downloadedBytes
    }
    return eta
}

/** Same drop-leading-zero-units shortening [humanEta] does, starting from raw
 *  seconds instead of the server's pre-formatted string -- there is no server
 *  string here to reformat, since this is an estimate computed on-device. */
private fun formatEtaSeconds(totalSeconds: Long): String {
    val d = totalSeconds / 86_400
    val h = (totalSeconds % 86_400) / 3_600
    val m = (totalSeconds % 3_600) / 60
    val s = totalSeconds % 60
    return listOf("${d}d", "${h}h", "${m}m", "${s}s")
        .dropWhile { it.dropLast(1).toIntOrNull() == 0 }
        .take(2)
        .joinToString(" ")
        .ifBlank { "0s" }
}
