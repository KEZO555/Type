package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and installs offline Vosk speech models on demand — one per language — so the APK stays
 * small (each model is ~30–48MB). Unpacks into internal storage; [VoiceDictation] loads it from there.
 * English keeps its original directory name so models installed by older versions aren't orphaned.
 */
object VoiceModel {
    private const val TAG = "VoiceModel"

    /** Per-language model directory. English uses the legacy name for backward compatibility. */
    fun dir(context: Context, code: String): File =
        File(context.filesDir, if (code == "en") "vosk-model" else "vosk-$code")

    fun isInstalled(context: Context, code: String): Boolean = File(dir(context, code), ".ok").exists()
    fun remove(context: Context, code: String) { dir(context, code).deleteRecursively() }

    /** Download + unpack [url] (a Vosk model zip) for language [code] on a background thread; callbacks
     *  fire on the main thread. */
    fun install(
        context: Context,
        code: String,
        url: String,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            val target = dir(context, code)
            val tmp = File(context.cacheDir, "vosk-$code.zip")
            try {
                target.deleteRecursively()
                download(url, tmp) { p -> main.post { onProgress(p) } }
                unzipStripTop(tmp, target)
                File(target, ".ok").writeText("ok")
                tmp.delete()
                Log.i(TAG, "model[$code] installed at ${target.absolutePath}")
                main.post { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "model[$code] install failed", e)
                tmp.delete(); target.deleteRecursively()
                main.post { onError(e.message ?: "download failed") }
            }
        }.start()
    }

    private val main = Handler(Looper.getMainLooper())

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Unzip, dropping the archive's single top-level folder so the model files land directly in [outDir]. */
    private fun unzipStripTop(zip: File, outDir: File) {
        outDir.mkdirs()
        val root = outDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val rel = entry.name.substringAfter('/')   // strip top-level "vosk-model-…/" folder
                if (rel.isEmpty()) { zis.closeEntry(); continue }
                val outFile = File(outDir, rel)
                if (!outFile.canonicalPath.startsWith(root)) throw SecurityException("zip path escape")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}
