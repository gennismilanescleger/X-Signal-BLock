// Offline map download with controls (SignalX 1.0.9 MapDownload.kt, ported; Fase 5).
//
// - Downloading is NEVER mandatory: the map is only the background drawing.
// - Resume with the Range header: the partial file is kept and continued
//   from where it stopped. Cancel deletes the partial file; pause keeps it.
// - Long timeouts (slow connection in Cuba) and retry; a failure says the
//   real reason, never a generic "Download failed".
// - Only ONE download runs at a time: MapManager owns the single Control
//   and the single progress strip, so there is no second set of controls.

package dev.ririxi.bunnylock.signalx

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

object MapDownload {
    const val CUBA_MAP_URL = "https://download.mapsforge.org/maps/v5/central-america/cuba.map"
    const val CUBA_MAP_SIZE_MB = 62

    /** Control of a download in progress: read by the download thread. */
    class Control {
        @Volatile var paused: Boolean = false
        @Volatile var cancelled: Boolean = false
        @Volatile var conn: HttpURLConnection? = null
    }

    enum class FailReason { NO_CONNECTION, CUT, NO_RESPONSE, SERVER_ERROR, NO_SPACE }

    sealed class Result {
        object Done : Result()
        object Paused : Result()
        object Cancelled : Result()
        class Failed(val reason: FailReason) : Result()
    }

    /** Real size of the file on the server (HEAD), or -1 when it cannot be known. */
    fun probeSize(url: String): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 20000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) -1 else conn.contentLengthLong
        } catch (_: Exception) {
            -1
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Downloads [url] to [dest] (written to dest + ".tmp" and renamed at
     * the end). A partial file resumes with Range. Calls [onProgress]
     * with (downloadedBytes, totalOr-1, bytesPerSecond) from a background
     * thread. Retries a cut connection up to 5 times; pause/cancel is
     * decided by [control]. The partial file is never deleted except on
     * cancel.
     */
    fun download(
        url: String,
        dest: File,
        control: Control,
        onProgress: (Long, Long, Long) -> Unit,
    ): Result {
        var attempt = 0
        while (attempt < 5) {
            attempt++
            when (val r = downloadOnce(url, dest, control, onProgress)) {
                is Result.Done -> return r
                is Result.Paused -> return r
                is Result.Cancelled -> return r
                is Result.Failed -> {
                    if (r.reason == FailReason.CUT && attempt < 5 && !control.cancelled) {
                        try {
                            Thread.sleep((attempt * 3000L).coerceAtMost(15000L))
                        } catch (_: InterruptedException) {
                        }
                        continue
                    }
                    return r
                }
            }
        }
        return Result.Failed(FailReason.CUT)
    }

    private fun downloadOnce(
        url: String,
        dest: File,
        control: Control,
        onProgress: (Long, Long, Long) -> Unit,
    ): Result {
        var conn: HttpURLConnection? = null
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        dest.parentFile?.mkdirs()
        try {
            val existing = if (tmp.exists()) tmp.length() else 0L
            conn = URL(url).openConnection() as HttpURLConnection
            control.conn = conn
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299 && code != 206) {
                return if (code == 416) {
                    // The partial file is already complete: validate it as a download.
                    if (tmp.length() > 0) {
                        if (dest.exists()) dest.delete()
                        if (tmp.renameTo(dest)) Result.Done else Result.Failed(FailReason.CUT)
                    } else {
                        Result.Failed(FailReason.SERVER_ERROR)
                    }
                } else {
                    Result.Failed(FailReason.SERVER_ERROR)
                }
            }
            val resumed = code == 206
            val total =
                if (resumed) {
                    conn.getHeaderField("Content-Range")
                        ?.substringAfter('/', "")
                        ?.toLongOrNull() ?: -1L
                } else {
                    conn.contentLengthLong
                }
            var downloaded = if (resumed) existing else 0L
            if (!resumed && existing > 0) {
                // The server does not accept Range: start from zero.
                tmp.delete()
                downloaded = 0L
            }
            val startDownloaded = downloaded
            val startTime = System.currentTimeMillis()
            conn.inputStream.use { input ->
                RandomAccessFile(tmp, "rw").use { out ->
                    out.seek(downloaded)
                    val buf = ByteArray(32 * 1024)
                    var n = input.read(buf)
                    while (n >= 0) {
                        if (control.cancelled) {
                            try {
                                tmp.delete()
                            } catch (_: Exception) {
                            }
                            return Result.Cancelled
                        }
                        if (control.paused) return Result.Paused
                        out.write(buf, 0, n)
                        downloaded += n
                        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                        val speed = (downloaded - startDownloaded) * 1000L / elapsed
                        onProgress(downloaded, total, speed)
                        n = input.read(buf)
                    }
                }
            }
            if (total > 0 && downloaded < total) return Result.Failed(FailReason.CUT)
            if (downloaded == 0L) return Result.Failed(FailReason.CUT)
            if (dest.exists()) dest.delete()
            return if (tmp.renameTo(dest)) Result.Done else Result.Failed(FailReason.CUT)
        } catch (_: java.net.UnknownHostException) {
            return Result.Failed(FailReason.NO_CONNECTION)
        } catch (_: java.net.SocketTimeoutException) {
            return Result.Failed(FailReason.NO_RESPONSE)
        } catch (e: java.io.IOException) {
            if ((e.message ?: "").contains("no space", ignoreCase = true)) {
                return Result.Failed(FailReason.NO_SPACE)
            }
            return if (control.cancelled) Result.Cancelled
            else if (control.paused) Result.Paused
            else Result.Failed(FailReason.CUT)
        } catch (_: Exception) {
            return Result.Failed(FailReason.CUT)
        } finally {
            control.conn = null
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
