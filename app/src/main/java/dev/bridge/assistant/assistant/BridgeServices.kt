package dev.bridge.assistant.assistant

import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.bridge.assistant.MainActivity

class BridgeVoiceService : VoiceInteractionService() {
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WakeEvents.ACTION_WAKE) {
                showSession(Bundle().apply { putBoolean("wake", true) }, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
            }
        }
    }
    override fun onReady() {
        super.onReady()
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(WakeEvents.ACTION_WAKE), Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, IntentFilter(WakeEvents.ACTION_WAKE))
            receiverRegistered = true
        }
    }
    override fun onShutdown() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onShutdown()
    }
}

class BridgeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession = BridgeSession(this)
}

object ScreenBuffer {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var extractedText: String? = null
    fun clear() { bitmap = null; extractedText = null }
}

class BridgeSession(private val owner: BridgeSessionService) : VoiceInteractionSession(owner) {
    private var lasso: ScreenLasso? = null
    private var info: TextView? = null
    private fun openAssistant(withSelection: Boolean, autoVoice: Boolean = false) {
        val intent = Intent(owner, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("screen_selection", withSelection)
            putExtra("auto_voice", autoVoice)
        }
        if (Build.VERSION.SDK_INT >= 34) startAssistantActivity(intent, Bundle.EMPTY)
        else owner.startActivity(intent)
        finish()
    }

    override fun onCreateContentView(): View {
        val root = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(owner).apply { text = "Open GPT · Выдели область экрана"; textSize = 19f })
        info = TextView(owner).apply { text = "Ожидание скриншота Android…" }
        root.addView(info)
        lasso = ScreenLasso(owner).also { canvas ->
            canvas.setScreenshot(ScreenBuffer.bitmap)
            root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.addView(Button(owner).apply {
            text = "Спросить GPT о выделенном"
            setOnClickListener {
                val clipped = lasso?.selectedBitmap()
                if (clipped == null) {
                    info?.text = "Обведи область пальцем. Скриншот может быть запрещён приложением."
                } else {
                    ScreenBuffer.bitmap = clipped
                    openAssistant(true)
                }
            }
        })
        root.addView(Button(owner).apply {
            text = "Открыть голосовые команды"
            setOnClickListener { openAssistant(false) }
        })
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ScreenBuffer.clear()
        if (args?.getBoolean("wake", false) == true) {
            openAssistant(false, true)
            return
        }
        info?.text = "Если система разрешит скриншот, его можно обвести пальцем."
        lasso?.setScreenshot(null)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        ScreenBuffer.bitmap = screenshot
        lasso?.setScreenshot(screenshot)
        info?.text = if (screenshot == null) "Доступ к экрану запрещён или отсутствует." else "Обведи фрагмент одним движением пальца."
    }

    override fun onHandleAssist(state: VoiceInteractionSession.AssistState) {
        val structure = state.assistStructure ?: return
        val collected = StringBuilder()
        var visited = 0
        fun walk(node: AssistStructure.ViewNode) {
            if (visited++ >= 1200 || collected.length > 12000) return
            node.text?.let { if (it.isNotBlank()) collected.append(it).append('\n') }
            for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
        ScreenBuffer.extractedText = collected.toString().take(12000)
    }
}
