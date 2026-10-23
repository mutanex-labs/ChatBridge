package dev.bridge.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.bridge.assistant.assistant.ScreenBuffer
import dev.bridge.assistant.core.Actions
import dev.bridge.assistant.core.CommandParser
import dev.bridge.assistant.core.LocalCommand
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var auth: ChatGptAuth
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var modelPicker: Spinner
    private var modelIds = emptyList<String>()
    private var recognizer: SpeechRecognizer? = null
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = ChatGptAuth(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        fun label(value: String) = TextView(this).apply { text = value; textSize = 17f }
        fun button(name: String, callback: () -> Unit) = Button(this).apply {
            text = name
            setOnClickListener { callback() }
            root.addView(this)
        }
        root.addView(label("GPT Bridge · Android assistant").apply { textSize = 24f })
        status = label("Account: ${auth.accountLabel}")
        root.addView(status)
        button("Continue with ChatGPT") {
            status.text = "Opening the system browser…"
            auth.begin(this) { result ->
                result.fold(onSuccess = { status.text = "Connected: $it"; refreshModels() },
                    onFailure = { status.text = "Sign-in failed: ${it.message}" })
            }
        }
        button("Disconnect account") { auth.signOut(); modelIds = emptyList(); status.text = "Disconnected" }
        root.addView(label("Model (from signed-in account):"))
        modelPicker = Spinner(this)
        root.addView(modelPicker)
        button("Refresh available models") { refreshModels() }
        input = EditText(this).apply {
            hint = "Позвони маме / открой Telegram / спроси GPT"
            minLines = 2
            setSingleLine(false)
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        button("Run command") { executeCommand(input.text.toString()) }
        button("Speak command") { startSpeech() }
        button("Ask GPT about current screen") {
            val image = ScreenBuffer.bitmap
            if (image == null) status.text = "No screenshot available from Android assistant invocation"
            else AlertDialog.Builder(this)
                .setTitle("Send screenshot to GPT?")
                .setMessage("The current screen image will be sent to OpenAI for this request only. Do not include sensitive data.")
                .setPositiveButton("Send") { _, _ -> askGpt(input.text.toString().ifBlank { "Объясни, что показано на экране" }, true) }
                .setNegativeButton("Cancel", null).show()
        }
        root.addView(label("Screen access depends on Android/OEM and the foreground app. Voice wake word is not available in this prototype."))
        if (auth.accountLabel != "Not connected") refreshModels()
    }

    private fun refreshModels() {
        status.text = "Loading models…"
        io.execute {
            try {
                val models = GptClient(auth).models()
                runOnUiThread {
                    modelIds = models.map { it.first }
                    val labels = models.map { it.second }
                    modelPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                    status.text = "Connected: ${auth.accountLabel}. ${models.size} models available."
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Cannot load models: ${e.message}" } }
        }
    }

    private fun executeCommand(text: String) {
        if (text.isBlank()) { status.text = "Enter or speak a command"; return }
        when (val command = CommandParser.parse(text)) {
            LocalCommand.AskGpt -> askGpt(text, false)
            else -> try { Actions(this) { status.text = it }.run(command) }
                catch (e: Exception) { status.text = "Action failed: ${e.message}" }
        }
    }

    private fun askGpt(text: String, withImage: Boolean) {
        val model = modelIds.getOrNull(modelPicker.selectedItemPosition)
        if (model == null) { status.text = "Connect ChatGPT and select a model first"; return }
        status.text = "Requesting $model…"
        val image = if (withImage) ScreenBuffer.bitmap else null
        io.execute {
            try {
                val result = GptClient(auth).ask(model, text, image)
                runOnUiThread { status.text = result.ifBlank { "Empty model response" } }
            } catch (e: Exception) {
                runOnUiThread { status.text = "GPT request failed: ${e.message}" }
            }
        }
    }

    private fun startSpeech() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "No Android speech recognizer is installed"
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Listening…" }
                override fun onBeginningOfSpeech() { }
                override fun onRmsChanged(rmsdB: Float) { }
                override fun onBufferReceived(buffer: ByteArray?) { }
                override fun onEndOfSpeech() { status.text = "Processing speech…" }
                override fun onError(error: Int) { status.text = "Speech recognition error $error" }
                override fun onResults(results: Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = words?.firstOrNull() ?: return
                    input.setText(transcript)
                    executeCommand(transcript)
                }
                override fun onPartialResults(partialResults: Bundle?) { }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }
            recognizer.startListening(intent)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 102 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSpeech()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        io.shutdownNow()
        super.onDestroy()
    }
}
