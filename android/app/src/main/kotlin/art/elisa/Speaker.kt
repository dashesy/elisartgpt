package art.elisa

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Reads an answer aloud with the phone's text-to-speech engine, picking the
 * voice from the script of the text: Persian for Persian letters, English
 * otherwise. Whether a voice exists is up to the phone; the button only shows
 * where it does.
 */
class Speaker(ctx: Context, private val onSpeaking: (Boolean) -> Unit) {
    // Compose state: the engine reports readiness a moment after the first frame,
    // and the speaker icons must appear then, not on the next unrelated redraw.
    private var ready by androidx.compose.runtime.mutableStateOf(false)
    private var tts: TextToSpeech? = null
    private val supported = mutableMapOf<String, Boolean>()

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                for (lang in listOf(FA, EN)) {
                    supported[lang.language] = tts!!.isLanguageAvailable(lang) >= TextToSpeech.LANG_AVAILABLE
                }
                // Which voices this phone has is the first question when "no speaker" is reported.
                android.util.Log.i("elisart", "tts engine=${tts!!.defaultEngine} voices=$supported")
                ready = true
                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = onSpeaking(true)
                    override fun onDone(id: String?) = onSpeaking(false)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = onSpeaking(false)
                })
            }
        }
    }

    fun canSpeak(text: String): Boolean = ready && supported[localeFor(text).language] == true

    fun speak(text: String) {
        val t = tts ?: return
        t.language = localeFor(text)
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    fun stop() {
        tts?.stop()
        onSpeaking(false)
    }

    fun release() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        val FA = Locale("fa", "IR")
        val EN = Locale.US

        fun localeFor(text: String): Locale = if (text.any { it in '\u0600'..'\u06FF' }) FA else EN
    }
}
