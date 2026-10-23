package dev.bridge.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.net.Uri
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
import dev.bridge.assistant.assistant.WakeWordService
import dev.bridge.assistant.assistant.WakeEvents
import dev.bridge.assistant.assistant.WakeModelInstaller
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
    private var pendingWakePermission = false
    private var activatedByWake = false
    private var rearmOnResume = false
    private var activityResumed = false

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
            confirmSelectedScreen()
        }
        root.addView(label("Open GPT: локальная офлайн-активация, включается вручную. Микрофон работает постоянно, расходует батарею."))
        button("Назначить GPT Bridge системным ассистентом") {
            val manager = getSystemService(RoleManager::class.java)
            if (manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 104)
            } else status.text = "Эта прошивка не предоставляет роль ассистента через RoleManager"
        }
        button("Скачать офлайн-модель Vosk (~40 МБ)") {
            AlertDialog.Builder(this).setTitle("Загрузить модель Open GPT?")
                .setMessage("Загрузка около 40 МБ с alphacephei.com. Проверка SHA-256 и распаковка в приватное хранилище. Потребуется около 200 МБ свободного места.")
                .setPositiveButton("Загрузить") { _, _ ->
                    status.text = "Загрузка и проверка модели…"
                    io.execute {
                        try {
                            WakeModelInstaller.downloadSmallEnglish(applicationContext)
                            runOnUiThread { status.text = "Модель готова: можно включать Open GPT" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Загрузка модели: ${e.message}" }
                        }
                    }
                }.setNegativeButton("Отмена", null).show()
        }
        button("Импортировать офлайн-модель Vosk (ZIP)") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            startActivityForResult(intent, 103)
        }
        button("Включить Open GPT") { enableWakeWord() }
        button("Выключить Open GPT") {
            stopService(Intent(this, WakeWordService::class.java))
            status.text = "Open GPT выключен"
        }
        root.addView(label("Для Open GPT сначала назначьте GPT Bridge системным ассистентом и импортируйте Vosk small English model."))
        if (intent.getBooleanExtra("screen_selection", false)) {
            input.setText("Объясни выделенную область экрана")
            confirmSelectedScreen()
        }
        if (intent.getBooleanExtra("auto_voice", false)) {
            activatedByWake = true
            android.os.Handler(mainLooper).postDelayed({ startSpeech() }, 400)
        }
        if (auth.accountLabel != "Not connected") refreshModels()
    }

    private fun confirmSelectedScreen() {
        if (ScreenBuffer.bitmap == null) {
            status.text = "Android не предоставил снимок экрана"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Отправить изображение в GPT?")
            .setMessage("Выбранная область будет отправлена в OpenAI после подтверждения. Не отправляйте личные данные.")
            .setPositiveButton("Отправить") { _, _ ->
                askGpt(input.text.toString().ifBlank { "Объясни, что показано на экране" }, true)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun enableWakeWord() {
        val role = getSystemService(RoleManager::class.java)
        if (!role.isRoleAvailable(RoleManager.ROLE_ASSISTANT) || !role.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            status.text = "Назначьте GPT Bridge ассистентом Android, иначе голосовой вызов не сможет показать сессию"
            return
        }
        if (!WakeModelInstaller.isInstalled(this)) {
            status.text = "Сначала импортируйте ZIP-модель Vosk"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWakePermission = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }
        try {
            startForegroundService(Intent(this, WakeWordService::class.java))
            status.text = "Open GPT включается: следите за постоянным уведомлением"
        } catch (e: Exception) {
            status.text = "Не удалось запустить микрофон: ${e.message}"
        }
    }

    @Deprecated("Uses the platform activity result callback for a local ZIP import")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 103 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        status.text = "Проверка и установка модели…"
        io.execute {
            try {
                WakeModelInstaller.install(applicationContext, uri)
                runOnUiThread { status.text = "Модель установлена. Можно включить Open GPT." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Импорт не удался: ${e.message}" }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("screen_selection", false)) {
            input.setText("Объясни выделенную область экрана")
            confirmSelectedScreen()
        }
        if (intent.getBooleanExtra("auto_voice", false)) {
            activatedByWake = true
            android.os.Handler(mainLooper).postDelayed({ startSpeech() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (rearmOnResume) {
            rearmOnResume = false
            resumeWakeDetection()
        }
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    private fun resumeWakeDetection() {
        if (!activatedByWake) return
        activatedByWake = false
        try {
            startService(Intent(this, WakeWordService::class.java).setAction(WakeEvents.ACTION_RESUME))
        } catch (e: Exception) {
            status.text = "Включите Open GPT повторно: ${e.message}"
        }
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
                override fun onError(error: Int) {
                    status.text = "Speech recognition error $error"
                    this@MainActivity.recognizer?.destroy()
                    this@MainActivity.recognizer = null
                    resumeWakeDetection()
                }
                override fun onResults(results: Bundle?) {
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val transcript = words?.firstOrNull()
                    if (transcript == null) {
                        resumeWakeDetection()
                        return
                    }
                    input.setText(transcript)
                    this@MainActivity.recognizer?.destroy()
                    this@MainActivity.recognizer = null
                    val local = CommandParser.parse(transcript)
                    if (activatedByWake && local is LocalCommand.Call) rearmOnResume = true
                    executeCommand(transcript)
                    if (!rearmOnResume) resumeWakeDetection()
                    else android.os.Handler(mainLooper).postDelayed({
                        if (activityResumed && rearmOnResume) {
                            rearmOnResume = false
                            resumeWakeDetection()
                        }
                    }, 1600)
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
        if (requestCode == 102 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (pendingWakePermission) {
                pendingWakePermission = false
                enableWakeWord()
            } else startSpeech()
        } else if (requestCode == 102) pendingWakePermission = false
    }

    override fun onDestroy() {
        recognizer?.destroy()
        io.shutdownNow()
        super.onDestroy()
    }
}
