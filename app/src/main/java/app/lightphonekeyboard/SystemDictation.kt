package app.lightphonekeyboard

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Dictation via the platform [SpeechRecognizer] (Google / whatever recognizer the device provides).
 *
 * This is the Hebrew backend: Vosk — used for fully-offline English (see [VoiceDictation]) — has no
 * Hebrew model, so Hebrew falls back to the system recognizer. It prefers on-device recognition when
 * the language pack is installed (EXTRA_PREFER_OFFLINE) but will use the network otherwise, so it is
 * NOT guaranteed offline/private the way the English path is.
 *
 * The platform recognizer is one-shot, so to mimic Vosk's continuous dictation we re-arm after every
 * final result and after the benign "no match / timeout" errors that just mean the user paused. Each
 * delivered phrase arrives through [onSegment]; the host commits it and we keep listening until [stop].
 *
 * All recognizer calls happen on the main thread, as the API requires.
 */
class SystemDictation(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    private var onPartial: (String) -> Unit = {}
    private var onSegment: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var locale = "he-IL"

    private val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context) || onDeviceAvailable

    /** Start continuous dictation in [bcp47] (e.g. "he-IL"). Callbacks fire on the main thread. */
    fun listen(
        bcp47: String,
        onPartial: (String) -> Unit,
        onSegment: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!available) { onError("Voice unavailable"); return }
        this.onPartial = onPartial
        this.onSegment = onSegment
        this.onError = onError
        this.locale = bcp47
        active = true
        main.post { startOnce() }
    }

    private fun startOnce() {
        if (!active) return
        destroyRecognizer()
        // Prefer the regular recognition service (usually Google, supports many online languages); fall
        // back to an on-device recognizer if that's all the phone has.
        val r = when {
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            onDeviceAvailable -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else -> { active = false; onError("No speech service on this phone"); return }
        }
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            // Some recognizers read only the IETF tag from this extra.
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(locale))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // No EXTRA_PREFER_OFFLINE: let the recognizer use its on-device Hebrew pack if present, or
            // the network otherwise.
        }
        try {
            r.startListening(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "startListening failed", e)
            onError("Voice error.")
            destroy()
        }
    }

    /** Re-arm shortly after a pause/benign error, so dictation feels continuous. */
    private fun restartSoon() {
        if (!active) return
        main.postDelayed({ if (active) startOnce() }, 250)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(results: Bundle?) {
            firstResult(results)?.let { if (it.isNotBlank()) onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let { if (it.isNotBlank()) onSegment(it) }
            restartSoon()   // keep listening across the natural pause that ended this phrase
        }

        // The recognizer pausing with nothing heard isn't a real failure during continuous dictation —
        // just re-arm. Anything else (audio/network/permission) surfaces and stops.
        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartSoon()
                else -> {
                    active = false
                    onError(messageFor(error))
                    destroyRecognizer()
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No network for voice."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Hebrew voice unavailable."
        else -> "Voice error."
    }

    /** Finish dictating: stop listening; the in-flight phrase still arrives via onResults. */
    fun stop() {
        active = false
        val r = recognizer ?: return
        runCatching { r.stopListening() }
        main.postDelayed({ destroyRecognizer() }, 400)
    }

    fun destroy() {
        active = false
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    companion object {
        private const val TAG = "SystemDictation"
        // BCP-47 locale handed to the platform recognizer; Hebrew is the only system-recognizer language
        // the keyboard currently switches to.
        const val HEBREW = "he-IL"
    }
}
