package art.elisa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Talk instead of type. Wraps Android's on-device SpeechRecognizer (Google's
 * engine on any normal phone) the way a chat app does: tap the mic, words show
 * up as they are recognized, it stops on a pause or a second tap. Persian is
 * the default because that is what the kid speaks.
 */
class Speech(private val ctx: Context) {
    var listening: Boolean = false
        private set

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** [onText] gets the running transcript; [onDone] fires once, with the last text or an error. */
    fun start(language: String, onText: (String) -> Unit, onDone: (error: String?) -> Unit) {
        stop()
        var last = ""
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { last = it; onText(it) }
            }

            override fun onResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { last = it; onText(it) }
                finish(null)
            }

            override fun onError(code: Int) {
                // A pause with nothing said is the normal way a turn ends, not a failure.
                val quiet = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                finish(if (quiet && last.isNotEmpty()) null else message(code))
            }

            private fun finish(error: String?) {
                if (!listening) return
                listening = false
                onDone(error)
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(type: Int, params: Bundle?) {}
        })
        listening = true
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            },
        )
    }

    /** Second tap: keep whatever was heard so far and hand the mic back. */
    fun stop() {
        recognizer?.let { r ->
            if (listening) r.stopListening()
            r.destroy()
        }
        recognizer = null
        listening = false
    }

    private fun message(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Try again?"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The app needs the microphone to hear you."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs the internet right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "One moment, still listening…"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This phone can't do voice in that language yet."
        else -> "Voice didn't work that time ($code)."
    }

    companion object {
        val LANGUAGES = listOf("fa-IR" to "فارسی", "en-US" to "English")
    }
}
