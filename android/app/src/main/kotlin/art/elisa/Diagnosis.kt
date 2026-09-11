package art.elisa

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * When the phone cannot reach the server, the person taps once and their mail
 * app opens with everything needed to see why: device, network type, VPN,
 * private DNS, whether the name resolves, whether 443 answers, and the error.
 * Mail is the only channel left when our own server is unreachable. The
 * address comes from .env at build time, so only builds handed out carry it.
 */
object Diagnosis {
    val available: Boolean get() = BuildConfig.SUPPORT_EMAIL.isNotBlank()

    suspend fun collect(ctx: Context, serverUrl: String, error: Throwable?): String = withContext(Dispatchers.IO) {
        val host = runCatching { URL(serverUrl).host }.getOrDefault(serverUrl)
        val lines = mutableListOf(
            "Elisa Art ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "Server: $serverUrl",
            "Time: ${java.util.Date()}",
            "",
            "Error: ${error?.let { chain(it) } ?: "none"}",
            "",
        )
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        lines += "Network: " + when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        } + (if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) ", through a VPN" else "") +
            (if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) ", validated" else ", NOT validated")
        val vpn = cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        lines += "VPN present: $vpn"
        lines += "Private DNS: ${Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: "off"}" +
            (Settings.Global.getString(ctx.contentResolver, "private_dns_specifier")?.let { " ($it)" } ?: "")
        lines += ""
        lines += "DNS $host: " + (withTimeoutOrNull(5_000) {
            runCatching { InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" } }
                .getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
        } ?: "timed out")
        SslipDns.address(host)?.let { ip ->
            // Straight to the address, no DNS: tells a DNS block apart from an IP block.
            lines += "TCP ${ip.hostAddress}:443 (no DNS): " + (withTimeoutOrNull(6_000) {
                val t = System.currentTimeMillis()
                runCatching { Socket().use { it.connect(InetSocketAddress(ip, 443), 5_000) }; "ok in ${System.currentTimeMillis() - t} ms" }
                    .getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "timed out")
        }
        for (port in listOf(443, 80)) {
            lines += "TCP $host:$port: " + (withTimeoutOrNull(6_000) {
                val t = System.currentTimeMillis()
                runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 5_000) }; "ok in ${System.currentTimeMillis() - t} ms" }
                    .getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
            } ?: "timed out")
        }
        lines += "HTTPS $serverUrl/health: " + (withTimeoutOrNull(8_000) {
            runCatching {
                (URL("$serverUrl/health").openConnection() as HttpsURLConnection).run {
                    connectTimeout = 5_000; readTimeout = 5_000
                    "$responseCode ${inputStream.bufferedReader().readText().take(80)}"
                }
            }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
        } ?: "timed out")
        // A well-known site tells us whether the internet works at all or only we are blocked.
        lines += "HTTPS https://www.google.com/generate_204: " + (withTimeoutOrNull(6_000) {
            runCatching {
                (URL("https://www.google.com/generate_204").openConnection() as HttpsURLConnection).run {
                    connectTimeout = 4_000; readTimeout = 4_000; responseCode.toString()
                }
            }.getOrElse { "FAILED ${it.javaClass.simpleName}: ${it.message}" }
        } ?: "timed out")
        lines.joinToString("\n")
    }

    fun send(ctx: Context, report: String) {
        // Also in logcat, for when the phone is on a cable instead of on its own.
        android.util.Log.i("elisart", report)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Elisa Art can't connect")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun chain(e: Throwable): String =
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
}
