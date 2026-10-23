package dev.bridge.assistant.core

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

class Actions(private val activity: Activity, private val report: (String) -> Unit) {
    fun run(command: LocalCommand) {
        when (command) {
            is LocalCommand.Call -> call(command.contact)
            is LocalCommand.Open -> open(command.application)
            is LocalCommand.Note -> note(command.body)
            LocalCommand.AskGpt -> report("Ask GPT: this action needs your account")
        }
    }

    private fun call(name: String) {
        if (activity.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 101)
            report("Grant contacts and call permissions, then repeat the command")
            return
        }
        val candidates = mutableListOf<Pair<String, String>>()
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER)
        activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%${name.removeSuffix("е").trim()}%"), null)?.use { cursor ->
            while (cursor.moveToNext() && candidates.size < 30) {
                candidates += cursor.getString(0) to cursor.getString(1)
            }
        }
        if (candidates.isEmpty()) {
            report("Contact '$name' was not found. Save it under this name or use the exact contact name.")
            return
        }
        val distinct = candidates.distinct()
        if (distinct.size == 1) dial(distinct[0].second)
        else AlertDialog.Builder(activity).setTitle("Choose contact")
            .setItems(distinct.map { "${it.first}: ${it.second}" }.toTypedArray()) { _, index ->
                dial(distinct[index].second)
            }.show()
    }

    private fun dial(number: String) {
        val uri = Uri.fromParts("tel", number, null)
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                activity.startActivity(Intent(Intent.ACTION_CALL, uri))
                report("Calling")
            } catch (e: SecurityException) {
                activity.startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
        } else {
            activity.startActivity(Intent(Intent.ACTION_DIAL, uri))
            report("Opened the dialer. Grant call permission to call directly.")
        }
    }

    private fun open(name: String) {
        val pm = activity.packageManager
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val matches = launchers.filter { it.loadLabel(pm).toString().contains(name, true) }
        if (matches.isEmpty()) {
            report("No app named '$name' is visible to the launcher")
            return
        }
        fun launch(index: Int) {
            val app = matches[index].activityInfo
            activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(app.packageName, app.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (matches.size == 1) launch(0) else AlertDialog.Builder(activity).setTitle("Choose app")
            .setItems(matches.map { it.loadLabel(pm).toString() }.toTypedArray()) { _, i -> launch(i) }.show()
    }

    private fun note(body: String) {
        val prefs = activity.getSharedPreferences("local_notes", Activity.MODE_PRIVATE)
        val old = prefs.getString("notes", "") ?: ""
        prefs.edit().putString("notes", listOf(old, body).filter { it.isNotBlank() }.joinToString("\n")).apply()
        report("Saved locally in GPT Bridge. Xiaomi Notes is not directly integrated.")
        AlertDialog.Builder(activity).setTitle("Share note to another app?")
            .setMessage(body).setPositiveButton("Share") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }
                activity.startActivity(Intent.createChooser(intent, "Choose notes app"))
            }.setNegativeButton("Keep local", null).show()
    }
}
