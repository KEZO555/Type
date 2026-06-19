package app.lightphonekeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads an offline autocorrect dictionary on demand for the languages that aren't bundled (Spanish,
 * French, German, Italian, Portuguese — English and Hebrew ship inside the APK). Keeps the APK small.
 *
 * The source is a "word<space>count" frequency list ([LangDef.dictUrl]). We stream it straight to a
 * compact local file, filtering on the way down to just that language's own letters (dropping the
 * noise — numbers, foreign words, fragments — that a raw subtitle frequency list carries) and keeping
 * the top [MAX_WORDS] by frequency. The result is the same on-disk format the bundled dictionaries use,
 * so [WordDictionary] loads it exactly like the bundled ones.
 */
object DictModel {
    private const val TAG = "DictModel"
    private const val MAX_WORDS = 30_000
    private const val MIN_LEN = 1
    private const val MAX_LEN = 16

    // Data version per downloadable dictionary. Bump a language's number whenever dicts/<code>.txt is
    // improved, so phones that already downloaded an older copy refresh themselves (see [refreshIfStale]).
    // Absent = version 1, the original baseline — so only languages listed here ever re-download.
    private val DICT_VERSIONS = mapOf("he" to 2)
    private const val META = "dict_meta"

    private val main = Handler(Looper.getMainLooper())
    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun currentVersion(code: String) = DICT_VERSIONS[code] ?: 1
    private fun meta(context: Context) = context.getSharedPreferences(META, Context.MODE_PRIVATE)
    private fun stampVersion(context: Context, code: String) =
        meta(context).edit().putInt("ver_$code", currentVersion(code)).apply()

    /** Where a language's downloaded dictionary lives (internal storage). */
    fun dictFile(context: Context, code: String): File = File(context.filesDir, "${code}_words.txt")

    fun isInstalled(context: Context, code: String): Boolean =
        dictFile(context, code).let { it.exists() && it.length() > 0 }

    /** Installed, but from an older data version than the app now ships → a better list is available. */
    private fun isStale(context: Context, code: String): Boolean =
        isInstalled(context, code) && meta(context).getInt("ver_$code", 1) < currentVersion(code)

    fun remove(context: Context, code: String) {
        dictFile(context, code).delete()
    }

    /** Download + filter on a background thread; callbacks fire on the main thread. */
    fun install(
        context: Context,
        def: LangDef,
        onProgress: (Int) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val url = def.dictUrl
        if (url == null) { onError("no dictionary"); return }
        val letters = def.autocorrectAlphabet.toHashSet()
        val app = context.applicationContext
        Thread {
            val tmp = File(app.cacheDir, "${def.code}_words.dl")
            val out = dictFile(app, def.code)
            try {
                downloadFiltered(url, tmp, letters) { p -> main.post { onProgress(p) } }
                out.delete()
                if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                stampVersion(app, def.code)
                Log.i(TAG, "dictionary installed: ${out.name} (${out.length()} bytes)")
                main.post { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "dictionary install failed", e)
                tmp.delete()
                main.post { onError(e.message ?: "download failed") }
            }
        }.start()
    }

    /**
     * If the installed dictionary is from an older data version, re-download the improved list once in
     * the background (no progress UI) and call [onUpdated] on the main thread when the new file is in
     * place. One attempt per process; a failure is logged and retried on the next launch.
     */
    fun refreshIfStale(context: Context, def: LangDef, onUpdated: () -> Unit) {
        val url = def.dictUrl ?: return
        val code = def.code
        if (!isStale(context, code)) return
        if (!refreshing.add(code)) return   // already attempted/attempting this session
        val app = context.applicationContext
        val letters = def.autocorrectAlphabet.toHashSet()
        Thread {
            val tmp = File(app.cacheDir, "${code}_words.upd")
            try {
                downloadFiltered(url, tmp, letters) {}
                val out = dictFile(app, code)
                out.delete()
                if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
                stampVersion(app, code)
                Log.i(TAG, "dictionary refreshed: $code -> v${currentVersion(code)}")
                main.post { onUpdated() }
            } catch (e: Exception) {
                Log.w(TAG, "dictionary refresh failed (retry next launch)", e)
                tmp.delete()
            }
        }.start()
    }

    /** Stream [url] line by line, keeping "<word> <count>" rows whose word is made only of [letters]
     *  (lowercased) and is [MIN_LEN]..[MAX_LEN] long, up to [MAX_WORDS]. Progress is reported by bytes. */
    private fun downloadFiltered(url: String, dest: File, letters: Set<Char>, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            var kept = 0
            val seen = HashSet<String>()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                dest.bufferedWriter(Charsets.UTF_8).use { writer ->
                    reader.forEachLine { line ->
                        read += line.length + 1
                        if (kept < MAX_WORDS) {
                            val sp = line.indexOf(' ')
                            if (sp > 0) {
                                val w = line.substring(0, sp).lowercase()
                                val count = line.substring(sp + 1).trim().toLongOrNull()
                                if (count != null && w.length in MIN_LEN..MAX_LEN &&
                                    w.all { it in letters } && seen.add(w)
                                ) {
                                    writer.append(w).append(' ').append(count.toString()).append('\n')
                                    kept++
                                }
                            }
                        }
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (kept == 0) throw RuntimeException("empty dictionary")
        } finally {
            conn.disconnect()
        }
    }
}
