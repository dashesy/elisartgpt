package art.elisa

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sideloaded apps update by hand, and "open the download in the browser" lost
 * people half-way: the file lands in Downloads and the old app keeps running,
 * banner and all. So the app fetches the APK itself (through its own client,
 * DNS-free like everything else) and hands it straight to Android's installer.
 * The first time, Android asks to allow installs from Elisa Art, then installs.
 */
object Updater {
    suspend fun install(ctx: Context, api: Api, v: AppVersion) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "elisart.apk")
        file.writeBytes(api.bytes(v.url))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
