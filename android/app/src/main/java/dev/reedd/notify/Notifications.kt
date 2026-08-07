package dev.reedd.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dev.reedd.MainActivity
import dev.reedd.R

/**
 * Notification channels and the three notifications this app posts.
 *
 * Transfers run as foreground services because they are long and the user will
 * leave the screen: a 500 MB download over wifi is exactly the case Android kills
 * a background job for.
 */
class Notifications(private val context: Context) {

    fun ensureChannels() {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFER, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Uploads to the conversion server and audiobook downloads"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_READY, "Finished books", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A book has finished converting and is ready to read along with"
            }
        )
    }

    /** @param progress 0..100, or null for an indeterminate bar. */
    fun transfer(title: String, text: String?, progress: Int?): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRANSFER)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .apply {
                if (progress == null) setProgress(0, 0, true)
                else setProgress(100, progress.coerceIn(0, 100), false)
            }
            .build()

    fun ready(bookTitle: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_READY)
            .setContentTitle("$bookTitle is ready")
            .setContentText("The audiobook and its timings are on your device")
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    fun failed(bookTitle: String, reason: String?): Notification =
        NotificationCompat.Builder(context, CHANNEL_READY)
            .setContentTitle("$bookTitle could not be converted")
            .setContentText(reason ?: "See the job log for details")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    /**
     * Wraps a notification for [androidx.work.CoroutineWorker.getForegroundInfo].
     *
     * From API 34 a foreground service must declare its type, and `dataSync` is
     * the one that covers moving a file to or from a server. The manifest merges
     * the same type onto WorkManager's own service.
     */
    fun foreground(notificationId: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    /**
     * POST_NOTIFICATIONS is runtime-granted from API 33. If the user declined, a
     * transfer still has to run, so this is a no-op rather than a failure.
     *
     * The check is written out here rather than delegated to a helper because
     * lint only recognises the guard when it is in the same function as the call.
     */
    fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CHANNEL_TRANSFER = "transfers"
        const val CHANNEL_READY = "ready"

        /**
         * Per-book notification ids, so two books transferring at once do not
         * overwrite each other's notification.
         */
        fun transferId(bookId: String): Int = 1_000 + (bookId.hashCode() and 0xFFFF)
        fun readyId(bookId: String): Int = 100_000 + (bookId.hashCode() and 0xFFFF)
    }
}
