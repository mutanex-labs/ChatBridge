package dev.bridge.assistant.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.bridge.assistant.MainActivity

class BridgeVoiceService : VoiceInteractionService() {
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WakeEvents.ACTION_WAKE) {
                showSession(Bundle().apply { putBoolean("wake", true) },
                    VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
            }
        }
    }

    override fun onReady() {
        super.onReady()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, receiver, IntentFilter(WakeEvents.ACTION_WAKE),
                WakeEvents.PERMISSION, null, ContextCompat.RECEIVER_NOT_EXPORTED)
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

/** The only static bitmap slot is a cropped selection awaiting user confirmation. */
object ScreenBuffer {
    private data class PendingImage(val bitmap: Bitmap, val owned: Boolean)
    @Volatile private var pending: PendingImage? = null

    @Synchronized fun setSelection(bitmap: Bitmap) {
        clear()
        pending = PendingImage(bitmap, owned = true)
    }

    @Synchronized fun takeSelection(): Bitmap? = pending?.also { pending = null }?.bitmap

    @Synchronized fun clear() {
        val previous = pending
        pending = null
        if (previous?.owned == true && !previous.bitmap.isRecycled) previous.bitmap.recycle()
    }
}

class BridgeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession = BridgeSession(this)
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
        try {
            startAssistantActivity(intent)
            finish()
        } catch (e: Exception) {
            if (withSelection) ScreenBuffer.clear()
            info?.text = "Не удалось открыть Chabi: ${e.message}"
        }
    }

    override fun onCreateContentView(): View {
        val root = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        root.addView(TextView(owner).apply { text = "Chabi · Выдели область экрана"; textSize = 19f })
        info = TextView(owner).apply { text = "Ожидание скриншота Android…" }
        root.addView(info)
        lasso = ScreenLasso(owner).also { canvas -> root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f)) }
        root.addView(Button(owner).apply {
            text = "Спросить GPT о выделенном"
            setOnClickListener {
                val clipped = lasso?.selectedBitmap()
                if (clipped == null) {
                    info?.text = "Обведи область одним движением. Скриншот может быть запрещён приложением."
                } else {
                    ScreenBuffer.setSelection(clipped)
                    lasso?.setScreenshot(null)
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
        lasso?.setScreenshot(null)
        if (args?.getBoolean("wake", false) == true) {
            openAssistant(false, true)
            return
        }
        info?.text = "Если система разрешит снимок экрана, его можно обвести пальцем."
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        lasso?.setScreenshot(screenshot)
        info?.text = if (screenshot == null) "Доступ к экрану запрещён или отсутствует."
        else "Обведи фрагмент одним движением пальца."
    }

    override fun onHide() {
        lasso?.setScreenshot(null)
        lasso = null
        info = null
        super.onHide()
    }
}
