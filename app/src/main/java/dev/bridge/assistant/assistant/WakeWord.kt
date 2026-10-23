package dev.bridge.assistant.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.WorkerThread
import dev.bridge.assistant.MainActivity
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean


object WakeEvents {
    const val ACTION_WAKE = "dev.bridge.assistant.WAKE_DETECTED"
    const val ACTION_STOP = "dev.bridge.assistant.WAKE_STOP"
    const val ACTION_RESUME = "dev.bridge.assistant.WAKE_RESUME"
    const val CHANNEL = "wake_foreground"
    const val NOTIFICATION_ID = 19
}

class WakeWordService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile private var microphone: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == WakeEvents.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED || !WakeModelInstaller.isInstalled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(WakeEvents.CHANNEL, "Open GPT microphone", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(WakeEvents.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, WakeEvents.CHANNEL)
            .setContentTitle("Open GPT · активен")
            .setContentText("Локальное прослушивание фразы. Нажмите, чтобы открыть настройки.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(WakeEvents.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(WakeEvents.NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            worker = Thread({ listen() }, "open-gpt-vosk").apply { isDaemon = true; start() }
        }
        return START_NOT_STICKY
    }

    @WorkerThread
    private fun listen() {
        var triggered = false
        try {
            Model(WakeModelInstaller.modelDir(this).absolutePath).use { model ->
                Recognizer(model, 16000f).use { recognizer ->
                    val bufferSize = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
                    require(bufferSize > 0) { "Unsupported microphone configuration" }
                    val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
                    microphone = recorder
                    require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                    recorder.startRecording()
                    val audio = ByteArray(bufferSize)
                    while (running.get()) {
                        val count = recorder.read(audio, 0, audio.size)
                        if (count < 0) break
                        if (count == 0) continue
                        if (recognizer.acceptWaveForm(audio, count)) {
                            val phrase = JSONObject(recognizer.result).optString("text")
                            if (WakePhrase.matches(phrase)) {
                                triggered = true
                                running.set(false)
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenGPTWake", "Recognition stopped; re-enable in the app", e)
        } finally {
            microphone?.let {
                try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) { }
                it.release()
            }
            microphone = null
            running.set(false)
            if (triggered) {
                getSystemService(NotificationManager::class.java).notify(WakeEvents.NOTIFICATION_ID,
                    Notification.Builder(this, WakeEvents.CHANNEL)
                        .setContentTitle("Open GPT · пауза на время команды")
                        .setContentText("Микрофон освобождён. Распознавание возобновится после команды.")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setOngoing(true).build())
                sendBroadcast(Intent(WakeEvents.ACTION_WAKE).setPackage(packageName))
            } else stopSelf()
        }
    }

    override fun onDestroy() {
        running.set(false)
        microphone?.let { try { it.stop() } catch (_: Exception) { } }
        super.onDestroy()
    }
}
