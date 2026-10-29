package com.starmano.splayer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates: asks s-player.bmk.si for the newest build, downloads it and hands it to
 * Android's installer. Every CI build is numbered (its versionCode) and published as release
 * build-N, which the site reports at /version.json.
 */
object Updates {
    private const val SITE = "https://s-player.bmk.si"
    private const val VERSION_URL = "$SITE/version.json"
    private const val APK_URL = "$SITE/s-player.apk"

    fun currentBuild(context: Context): Long {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    /** The newest published build number. Blocks, so call it off the main thread. */
    fun latestBuild(): Long {
        val connection = open(VERSION_URL)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Version check failed: HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseBuildNumber(body) ?: throw IOException("Version check failed: $body")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the newest APK into the app's cache and returns it. [onProgress] gets whole
     * percentages (or -1 if the size is unknown) on the calling thread. Blocks, so call it off
     * the main thread.
     */
    fun download(context: Context, onProgress: (percent: Int) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "s-player.apk.part")
        val apk = File(directory, "s-player.apk")
        val connection = open(APK_URL)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var reported = Int.MIN_VALUE
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = if (total > 0) (copied * 100 / total).toInt() else -1
                        if (percent != reported) {
                            reported = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        apk.delete()
        if (!partial.renameTo(apk)) throw IOException("Couldn't save the update")

        // Make sure a broken or unexpected download never reaches the installer.
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (archive?.packageName != context.packageName) {
            apk.delete()
            throw IOException("The download is not an S Player update")
        }
        return apk
    }

    /** Opens Android's installer for [apk], which asks the user to confirm the update. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Cache-Control", "no-cache")
        }
}

/** Reads `build` from the site's /version.json, e.g. `{"build":12,"apk":"/s-player.apk"}`. */
internal fun parseBuildNumber(json: String): Long? =
    Regex("\"build\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
