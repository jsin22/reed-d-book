package dev.reedd.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
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

    // Conversions and downloads report progress through notifications, and both
    // outlive this screen. Asked for once on first open; declining only costs the
    // notifications, not the transfers.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                icon = {
                    if (importing) CircularProgressIndicator(Modifier.size(18.dp))
                    else Icon(Icons.Filled.Add, contentDescription = null)
                },
                text = { Text(if (importing) "Importing" else "Add book") },
            )
        },
    ) { padding ->
        if (books.isEmpty()) {
            EmptyLibrary(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { if (book.isPlayable) onOpenBook(book.id) else onOpenDetail(book.id) },
                        onLongClick = { onOpenDetail(book.id) },
                    )
                }
            }
        }
    }

    if (showImport) {
        ImportSheet(
            viewModel = viewModel,
            onDismiss = {
                showImport = false
                pickedUri = null
            },
            onConfirm = { voice, speed ->
                pickedUri?.let { viewModel.importAndUpload(it, voice, speed) }
                showImport = false
                pickedUri = null
            },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp)) {
            Cover(book, Modifier.size(width = 56.dp, height = 80.dp))
            Column(
                Modifier
                    .padding(start = 12.dp)
                    .fillMaxWidth()
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
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StageChip(book)
                    if (book.stage() == BookStage.FAILED || book.stage() == BookStage.LOST) {
                        Text(
                            "Details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onLongClick),
                        )
                    }
                }
                ProgressLine(book)
            }
        }
    }
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
private fun StageChip(book: BookEntity) {
    val (label, tint) = when (book.stage()) {
        BookStage.LOCAL -> "On device only" to MaterialTheme.colorScheme.onSurfaceVariant
        BookStage.UPLOADING -> "Uploading" to MaterialTheme.colorScheme.primary
        BookStage.QUEUED -> "Queued on server" to MaterialTheme.colorScheme.primary
        BookStage.CONVERTING -> buildString {
            append("Converting ${book.jobProgress}%")
            book.jobEta?.let { append(" · ${it.humanEta()}") }
        } to MaterialTheme.colorScheme.primary
        BookStage.DOWNLOADING -> "Downloading" to MaterialTheme.colorScheme.primary
        BookStage.READY -> "Ready to read along" to MaterialTheme.colorScheme.primary
        BookStage.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        BookStage.LOST -> "Job lost on server" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        enabled = false,
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
