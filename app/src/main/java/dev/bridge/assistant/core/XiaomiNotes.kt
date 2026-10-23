package dev.bridge.assistant.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent

object XiaomiNotes {
    private const val PACKAGE = "com.miui.notes"

    fun sendToNotes(activity: Activity, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(PACKAGE)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
