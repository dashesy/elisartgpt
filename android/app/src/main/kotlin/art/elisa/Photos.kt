package art.elisa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photos a person attaches to a request. Phone cameras produce 3-12 MB files;
 * the model only needs enough pixels to recognize a hand or a wristband, so
 * everything is shrunk to [MAX_SIDE] px and re-encoded as JPEG before upload.
 */
object Photos {
    const val MAX = 4
    private const val MAX_SIDE = 1280

    /** A fresh content URI the camera app can write into; lives in our cache. */
    fun newShot(ctx: Context): Uri {
        val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
        // Shots are only useful for the request they were taken for; sweep old ones.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000 }?.forEach { it.delete() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    }

    /** Decode at a reduced size, apply the EXIF rotation, cap the longest side, return JPEG bytes. */
    suspend fun shrink(ctx: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        // Power-of-two subsampling gets close cheaply; the exact cap is applied below.
        val opts = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize * 2) >= MAX_SIDE) inSampleSize *= 2
        }
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        val orientation = cr.openInputStream(uri)?.use { runCatching { ExifInterface(it).rotationDegrees }.getOrDefault(0) } ?: 0
        if (orientation != 0) m.postRotate(orientation.toFloat())
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
