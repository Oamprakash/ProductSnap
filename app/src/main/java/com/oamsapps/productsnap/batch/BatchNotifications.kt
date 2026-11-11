package com.oamsapps.productsnap.batch

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.oamsapps.productsnap.MainActivity
import com.oamsapps.productsnap.R
import java.util.UUID

object BatchNotifications {

    private const val CHANNEL_ID = "batch_saving"
    private const val PROGRESS_ID = 1001
    private const val DONE_ID = 1002

    fun progress(context: Context, workId: UUID, done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)
        val stop = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_batch)
            .setContentTitle(context.getString(R.string.notif_saving_title))
            .setContentText(context.getString(R.string.notif_progress, done, total))
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openBatch(context))
            .addAction(0, context.getString(R.string.stop), stop)
            .build()
        return ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Only shown when the user isn't looking at the app; otherwise the snackbar covers it. */
    @SuppressLint("MissingPermission") // checked via areNotificationsEnabled()
    fun showFinished(context: Context, saved: Int, total: Int) {
        if (isAppInForeground()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_batch)
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(context.getString(R.string.batch_saved_message, saved, total))
            .setAutoCancel(true)
            .setContentIntent(openBatch(context))
            .build()
        manager.notify(DONE_ID, notification)
    }

    private fun openBatch(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_BATCH, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notif_channel_desc)
                }
            )
        }
    }

    private fun isAppInForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
