package dev.bridge.assistant.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.bridge.assistant.MainActivity

class BridgeVoiceService : VoiceInteractionService()

class BridgeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession = BridgeSession(this)
}

object ScreenBuffer {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var extractedText: String? = null
    fun clear() { bitmap = null; extractedText = null }
}

class BridgeSession(private val owner: BridgeSessionService) : VoiceInteractionSession(owner) {
    override fun onCreateContentView(): android.view.View {
        val root = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(owner).apply { text = "GPT Bridge · System Assistant"; textSize = 20f })
        root.addView(Button(owner).apply {
            text = "Open voice and actions"
            setOnClickListener {
                val intent = Intent(owner, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (Build.VERSION.SDK_INT >= 34) startAssistantActivity(intent, Bundle.EMPTY)
                else owner.startActivity(intent)
                finish()
            }
        })
        return root
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        ScreenBuffer.bitmap = screenshot
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        ScreenBuffer.extractedText = content?.webUri?.toString()
    }
}
