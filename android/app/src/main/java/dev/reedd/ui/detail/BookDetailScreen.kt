package dev.reedd.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.reedd.data.db.BookEntity
import dev.reedd.data.db.DownloadState
import dev.reedd.ui.library.BookStage
import dev.reedd.ui.library.humanEta
import dev.reedd.ui.library.stage

/**
 * Everything about one book's conversion, and the buttons that act on it.
 *
 * This is where a failure is explained rather than summarised: the server's error
 * text, and the audiblez log behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    viewModel: BookDetailViewModel,
    onBack: () -> Unit,
    onRead: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val loadingLog by viewModel.loadingLog.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(book?.title ?: "Book", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val current = book
        if (current == null) {
            Text("This book is no longer in the library.", Modifier.padding(24.dp))
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(current)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.isPlayable) {
                    Button(onClick = { onRead(current.id) }) { Text("Read along") }
                }
                when (current.stage()) {
                    BookStage.LOCAL, BookStage.FAILED, BookStage.LOST ->
                        Button(onClick = { onRetryUpload(current.id) }) {
                            Text(if (current.jobId == null) "Send to server" else "Send again")
                        }
                    BookStage.QUEUED, BookStage.CONVERTING, BookStage.UPLOADING ->
                        OutlinedButton(onClick = { onCancel(current.id) }) { Text("Cancel") }
                    BookStage.DOWNLOADING ->
                        if (current.downloadState == DownloadState.FAILED) {
                            Button(onClick = { onRetryDownload(current.id) }) { Text("Resume download") }
                        }
                    BookStage.READY -> Unit
                }
                TextButton(onClick = { onDelete(current.id) }) { Text("Delete") }
            }

            Details(current)

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Server log", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = viewModel::loadLog, enabled = !loadingLog) {
                        Text(if (loadingLog) "Loading" else "Fetch")
                    }
                }
                log?.let {
                    Card {
                        Text(
                            it,
                            modifier = Modifier
                                .padding(12.dp)
                                .horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(book: BookEntity) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(statusHeadline(book), style = MaterialTheme.typography.titleMedium)

            when (book.stage()) {
                BookStage.CONVERTING -> {
                    LinearProgressIndicator(
                        progress = { book.jobProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val eta = book.jobEta?.humanEta()
                    Text(
                        buildString {
                            append("${book.jobProgress}%")
                            if (book.jobChaptersDone > 0) append(" · ${book.jobChaptersDone} chapters done")
                            if (eta != null) append(" · about $eta left")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                BookStage.UPLOADING -> if (book.sizeBytes > 0) {
                    LinearProgressIndicator(
                        progress = { (book.uploadedBytes.toFloat() / book.sizeBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${book.uploadedBytes.mb()} of ${book.sizeBytes.mb()}", style = MaterialTheme.typography.bodySmall)
                }

                BookStage.DOWNLOADING -> {
                    if (book.downloadTotalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { (book.downloadedBytes.toFloat() / book.downloadTotalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (book.downloadTotalBytes > 0) {
                            "${book.downloadedBytes.mb()} of ${book.downloadTotalBytes.mb()}"
                        } else {
                            "Waiting for the download to start"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                BookStage.READY -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${book.totalChunks} sentences mapped to the audio.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Surfaced rather than hidden: a book that highlights badly
                    // should say so instead of just behaving oddly.
                    if (book.totalChunks > 0) {
                        val percent = book.alignedChunks * 100 / book.totalChunks
                        Text(
                            "$percent% of them could be matched to a place on the page" +
                                if (percent < 90) " — the rest will play without highlighting." else ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (percent < 90) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> Unit
            }

            val error = book.jobError ?: book.uploadError ?: book.downloadError
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (book.needsReupload) {
                Text(
                    "The server no longer has this job. Its data directory was probably cleared. Sending the book again will start a new conversion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun statusHeadline(book: BookEntity): String = when (book.stage()) {
    BookStage.LOCAL -> "On this device only"
    BookStage.UPLOADING -> "Uploading to the server"
    BookStage.QUEUED -> "Queued on the server"
    BookStage.CONVERTING -> "Converting"
    BookStage.DOWNLOADING -> "Downloading the audiobook"
    BookStage.READY -> "Ready to read along"
    BookStage.FAILED -> "Something went wrong"
    BookStage.LOST -> "The server lost this job"
}

@Composable
private fun Details(book: BookEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row2("File", book.originalFilename)
        Row2("Size", book.sizeBytes.mb())
        book.author?.let { Row2("Author", it) }
        book.voice?.let { Row2("Voice", it) }
        book.speed?.let { Row2("Speed", "${it}x") }
        book.jobId?.let { Row2("Job", it) }
        book.audiobookBytes?.let { Row2("Audiobook", it.mb()) }
        book.audioDurationMs?.let { Row2("Audio length", it.duration()) }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

private fun Long.mb(): String = when {
    this <= 0 -> "-"
    this < 1024 * 1024 -> "${this / 1024} kB"
    else -> "%.1f MB".format(this / 1024.0 / 1024.0)
}

private fun Long.duration(): String {
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%dh %02dm".format(hours, minutes) else "%dm %02ds".format(minutes, seconds)
}
