package art.elisa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the app alive while a drawing is in progress. Android freezes an app
 * soon after it leaves the screen and, since 15, tears its sockets down with
 * it; a minute-long drawing would then wait for the person to come back, and
 * the picture would not be saved to Photos until they did. A foreground
 * service is the one thing that exempts a process from that, at the price of
 * a small "drawing…" notification for as long as it runs.
 */
class DrawingService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Drawing in progress", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_brush)
            .setContentTitle("Elisa Art is drawing…")
            .setContentText("The picture will be here when it's ready.")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 1, note, type)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "drawing"

        // Both are best-effort: starting one from the background is refused on
        // Android 12+, and a drawing must go on regardless.
        fun begin(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, DrawingService::class.java)) }
        fun end(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, DrawingService::class.java)) }
    }
}
