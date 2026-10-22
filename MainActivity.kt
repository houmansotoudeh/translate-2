package com.example.talktranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    /** حالت خودکار = تشخیص زبان؛ دو حالت دیگر برای وقتی که تشخیص خودکار روی گوشی خوب کار نکند */
    private enum class Mode(val label: String) {
        AUTO("حالت: تشخیص خودکار زبان  (برای تغییر لمس کنید)"),
        FA_TO_EN("حالت: فارسی به انگلیسی  (برای تغییر لمس کنید)"),
        EN_TO_FA("حالت: انگلیسی به فارسی  (برای تغییر لمس کنید)")
    }

    private var mode = Mode.AUTO

    private lateinit var talkButton: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var sourceText: TextView
    private lateinit var translatedText: TextView
    private lateinit var modeText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var modelsReady = false

    private var isHolding = false        // انگشت روی دکمه است
    private var awaitingFinal = false    // انگشت برداشته شده و منتظر نتیجه نهایی هستیم
    private val collected = StringBuilder()
    private var lastPartial = ""

    private val faToEn: Translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.PERSIAN)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }
    private val enToFa: Translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build()
        )
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            setStatus(
                if (granted) "آماده است. دکمه را نگه دارید و صحبت کنید."
                else "بدون اجازه میکروفون برنامه کار نمی‌کند."
            )
        }

    private val finalizeTimeout = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        talkButton = findViewById(R.id.talkButton)
        statusText = findViewById(R.id.statusText)
        sourceText = findViewById(R.id.sourceText)
        translatedText = findViewById(R.id.translatedText)
        modeText = findViewById(R.id.modeText)

        modeText.text = mode.label
        modeText.setOnClickListener {
            mode = Mode.values()[(mode.ordinal + 1) % Mode.values().size]
            modeText.text = mode.label
        }

        talkButton.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startHolding()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.scaleX = 1f; v.scaleY = 1f
                    stopHolding()
                    if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }

        tts = TextToSpeech(this, this)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("سرویس تشخیص گفتار روی این گوشی نیست. برنامه Google را نصب یا به‌روز کنید.")
        }

        if (!hasMicPermission()) micPermission.launch(Manifest.permission.RECORD_AUDIO)

        downloadModels()
    }

    // ---------------- مدل‌های ترجمه ----------------

    private fun downloadModels() {
        setStatus("در حال دانلود مدل‌های ترجمه (فقط بار اول، به اینترنت نیاز دارد)…")
        val conditions = DownloadConditions.Builder().build()
        faToEn.downloadModelIfNeeded(conditions)
            .continueWithTask { enToFa.downloadModelIfNeeded(conditions) }
            .addOnSuccessListener {
                modelsReady = true
                setStatus("آماده است. دکمه را نگه دارید و صحبت کنید.")
            }
            .addOnFailureListener {
                setStatus("دانلود مدل ترجمه ناموفق بود. اینترنت را بررسی و برنامه را دوباره باز کنید.")
            }
    }

    // ---------------- لمس دکمه ----------------

    private fun startHolding() {
        if (!hasMicPermission()) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (!modelsReady) {
            Toast.makeText(this, "مدل‌های ترجمه هنوز آماده نیستند", Toast.LENGTH_SHORT).show(); return
        }
        tts?.stop()
        handler.removeCallbacks(finalizeTimeout)
        isHolding = true
        awaitingFinal = false
        collected.clear()
        lastPartial = ""
        sourceText.text = ""
        translatedText.text = ""
        setStatus("در حال گوش دادن…")
        startRecognizer()
    }

    private fun stopHolding() {
        if (!isHolding) return
        isHolding = false
        awaitingFinal = true
        setStatus("در حال ترجمه…")
        recognizer?.stopListening()
        // اگر سرویس گفتار به موقع جواب نداد، با همان متنی که داریم ادامه بده
        handler.postDelayed(finalizeTimeout, 4000)
    }

    // ---------------- تشخیص گفتار ----------------

    private fun startRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            it.startListening(buildIntent())
        }
    }

    private fun buildIntent(): Intent {
        val primary = if (mode == Mode.EN_TO_FA) "en-US" else "fa-IR"
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primary)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primary)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // تا وقتی انگشت روی دکمه است با سکوت کوتاه قطع نشود
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
            if (mode == Mode.AUTO) {
                // سرویس گفتار گوگل اجازه می‌دهد زبان دوم هم شنیده شود
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US"))
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            if (isHolding) {
                val s = 1f + rmsdB.coerceIn(0f, 10f) / 80f
                talkButton.scaleX = s; talkButton.scaleY = s
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotEmpty()) {
                lastPartial = text
                sourceText.text = (collected.toString() + " " + lastPartial).trim()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text.isNotEmpty()) collected.append(' ').append(text)
            lastPartial = ""
            sourceText.text = collected.toString().trim()
            if (isHolding) {
                // کاربر هنوز دکمه را نگه داشته؛ دوباره گوش بده
                handler.post { if (isHolding) startRecognizer() }
            } else {
                finish()
            }
        }

        override fun onError(error: Int) {
            if (isHolding) {
                val delay = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 300L else 50L
                handler.postDelayed({ if (isHolding) startRecognizer() }, delay)
                return
            }
            if (awaitingFinal) {
                finish(); return
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setStatus("اجازه میکروفون داده نشده است.")
            }
        }
    }

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""

    /** وقتی انگشت برداشته شد و متن نهایی آماده شد */
    private fun finish() {
        if (!awaitingFinal) return
        awaitingFinal = false
        handler.removeCallbacks(finalizeTimeout)
        val text = (collected.toString() + " " + lastPartial).trim()
        if (text.isEmpty()) {
            setStatus("چیزی شنیده نشد. دوباره امتحان کنید.")
            return
        }
        sourceText.text = text
        translateAndSpeak(text)
    }

    // ---------------- ترجمه و پخش صدا ----------------

    private fun translateAndSpeak(text: String) {
        val isPersian = when (mode) {
            Mode.FA_TO_EN -> true
            Mode.EN_TO_FA -> false
            Mode.AUTO -> looksPersian(text)
        }
        val translator = if (isPersian) faToEn else enToFa
        setStatus(if (isPersian) "فارسی شنیده شد ← ترجمه به انگلیسی" else "English heard → ترجمه به فارسی")

        translator.translate(text)
            .addOnSuccessListener { out ->
                translatedText.text = out
                speak(out, if (isPersian) Locale.US else Locale("fa", "IR"))
            }
            .addOnFailureListener {
                setStatus("ترجمه ناموفق بود: ${it.localizedMessage}")
            }
    }

    /** اگر بیشتر حروف متن فارسی/عربی باشد، متن فارسی است */
    private fun looksPersian(text: String): Boolean {
        var fa = 0
        var en = 0
        for (c in text) {
            when {
                c in '\u0600'..'\u06FF' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF' -> fa++
                c in 'a'..'z' || c in 'A'..'Z' -> en++
            }
        }
        return fa >= en
    }

    private fun speak(text: String, locale: Locale) {
        val engine = tts
        if (!ttsReady || engine == null) {
            setStatus("موتور تبدیل متن به گفتار آماده نیست."); return
        }
        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            setStatus(
                if (locale.language == "fa")
                    "موتور صوتی گوشی شما فارسی ندارد. یک موتور TTS فارسی نصب کنید (راهنما در README)."
                else
                    "صدای انگلیسی روی گوشی نصب نیست. از تنظیمات «تبدیل متن به گفتار» نصب کنید."
            )
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation")
        setStatus("آماده است. دکمه را نگه دارید و صحبت کنید.")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
    }

    // ---------------- کمکی ----------------

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun setStatus(s: String) {
        statusText.text = s
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        faToEn.close()
        enToFa.close()
        super.onDestroy()
    }
}
