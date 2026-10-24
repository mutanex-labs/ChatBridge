package dev.bridge.assistant.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.drawable.Icon
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

object WakeEvents {
    const val ACTION_WAKE = "dev.bridge.assistant.WAKE_DETECTED"
    const val ACTION_STOP = "dev.bridge.assistant.WAKE_STOP"
    const val ACTION_RESUME = "dev.bridge.assistant.WAKE_RESUME"
    const val PERMISSION = "dev.bridge.assistant.permission.INTERNAL_BROADCAST"
    const val CHANNEL = "wake_foreground"
    const val NOTIFICATION_ID = 19
}

class WakeWordService : Service() {
    private val detection = WakeDetectionGate()
    private val recorderLock = Any()
    private var worker: Thread? = null
    @Volatile private var microphone: AudioRecord? = null
    @Volatile private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == WakeEvents.ACTION_STOP) {
            detection.stop()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !WakeModelInstaller.isInstalled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            WakeEvents.CHANNEL, "Open GPT microphone", NotificationManager.IMPORTANCE_LOW
        ))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, WakeWordService::class.java).setAction(WakeEvents.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, WakeEvents.CHANNEL)
            .setContentTitle("Open GPT · активен")
            .setContentText("Локальное прослушивание фразы. Нажмите, чтобы открыть настройки.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Остановить", stop
            ).build())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(WakeEvents.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(WakeEvents.NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("OpenGPTWake", "Could not start the foreground microphone service", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (detection.begin()) {
            try {
                worker = Thread({ listen() }, "open-gpt-vosk").apply { isDaemon = true; start() }
            } catch (e: Exception) {
                detection.stop()
                Log.e("OpenGPTWake", "Could not start the Vosk worker", e)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    @WorkerThread
    private fun listen() {
        var triggered = false
        try {
            Model(WakeModelInstaller.modelDir(this).absolutePath).use { model ->
                Recognizer(model, 16000f).use { recognizer ->
                    val minimumBufferSize = AudioRecord.getMinBufferSize(
                        16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    require(minimumBufferSize > 0) { "Unsupported microphone configuration" }
                    val bufferSize = maxOf(minimumBufferSize, 3200)
                    check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        "Microphone permission was revoked"
                    }
                    val recorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2
                    )
                    synchronized(recorderLock) {
                        if (!detection.isActive() || destroyed) {
                            recorder.release()
                            return
                        }
                        microphone = recorder
                        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
                        recorder.startRecording()
                    }
                    val audio = ByteArray(bufferSize)
                    while (detection.isActive()) {
                        val count = recorder.read(audio, 0, audio.size)
                        check(count >= 0) { "AudioRecord read failed ($count)" }
                        if (count == 0) {
                            Thread.sleep(10)
                            continue
                        }
                        if (recognizer.acceptWaveForm(audio, count)) {
                            val phrase = JSONObject(recognizer.result).optString("text")
                            if (WakePhrase.matches(phrase)) {
                                triggered = true
                                detection.stop()
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("OpenGPTWake", "Recognition stopped; re-enable in the app", e)
        } finally {
            val recorder = synchronized(recorderLock) { microphone.also { microphone = null } }
            recorder?.let {
                try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) { }
                try { it.release() } catch (_: Exception) { }
            }
            detection.stop()
            if (triggered && !destroyed) {
                getSystemService(NotificationManager::class.java).notify(WakeEvents.NOTIFICATION_ID,
                    Notification.Builder(this, WakeEvents.CHANNEL)
                        .setContentTitle("Open GPT · пауза на время команды")
                        .setContentText("Микрофон освобождён. Распознавание возобновится после команды.")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .setOngoing(true).build())
                sendBroadcast(Intent(WakeEvents.ACTION_WAKE).setPackage(packageName), WakeEvents.PERMISSION)
            } else {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        detection.stop()
        synchronized(recorderLock) {
            microphone?.let {
                try { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } catch (_: Exception) { }
            }
        }
        super.onDestroy()
    }
}
