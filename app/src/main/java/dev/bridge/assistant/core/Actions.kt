package dev.bridge.assistant.core

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

class Actions(private val activity: Activity, private val report: (String) -> Unit) {
    private data class ContactRow(val name: String, val number: String)

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
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 101)
            report("Grant contacts permission, then repeat the command")
            return
        }
        val candidates = mutableListOf<ContactRow>()
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER)
        activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns,
            null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC")?.use { cursor ->
            while (cursor.moveToNext() && candidates.size <= MAX_CONTACT_ROWS) {
                val displayName = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                if (displayName.isNotBlank() && number.isNotBlank()) candidates += ContactRow(displayName, number)
            }
        }
        if (candidates.size > MAX_CONTACT_ROWS) {
            report("Too many contacts to resolve safely. Use a more specific contact name.")
            return
        }
        val matches = ContactResolver.matching(name, candidates.map { ContactNumber(it.name, it.number) })
        if (matches.isEmpty()) {
            report("Contact '$name' was not found. Save it under this name or use the exact contact name.")
            return
        }
        if (matches.size == 1) dial(matches[0].number)
        else AlertDialog.Builder(activity).setTitle("Choose contact")
            .setItems(matches.map { "${it.displayName}: ${it.number}" }.toTypedArray()) { _, index ->
                if (index in matches.indices) dial(matches[index].number)
            }.show()
    }

    private fun dial(number: String) {
        val uri = Uri.fromParts("tel", number, null)
        if (activity.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                activity.startActivity(Intent(Intent.ACTION_CALL, uri))
                report("Calling")
            } catch (e: SecurityException) {
                openDialer(uri)
            } catch (e: ActivityNotFoundException) {
                report("No phone app can place this call")
            }
        } else {
            openDialer(uri)
        }
    }

    private fun openDialer(uri: Uri) {
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, uri))
            report("Opened the dialer. Confirm the call there.")
        } catch (_: ActivityNotFoundException) {
            report("No phone app can open the dialer")
        } catch (_: SecurityException) {
            report("The phone app could not be opened")
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
            try {
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.packageName, app.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                report("The selected app is no longer available")
            } catch (_: SecurityException) {
                report("Android blocked the selected app from opening")
            }
        }
        if (matches.size == 1) launch(0) else AlertDialog.Builder(activity).setTitle("Choose app")
            .setItems(matches.map { it.loadLabel(pm).toString() }.toTypedArray()) { _, i -> launch(i) }.show()
    }

    private fun note(body: String) {
        val prefs = activity.getSharedPreferences("local_notes", Activity.MODE_PRIVATE)
        val old = prefs.getString("notes", "") ?: ""
        prefs.edit().putString("notes", listOf(old, body).filter { it.isNotBlank() }.joinToString("\n")).apply()
        if (XiaomiNotes.sendToNotes(activity, body)) {
            report("Текст отправлен в Xiaomi Notes. Проверьте экран и сохраните заметку: подтверждения записи через API нет.")
            return
        }
        report("Xiaomi Notes не поддерживает доступную отправку текста. Копия сохранена локально.")
        AlertDialog.Builder(activity).setTitle("Передать заметку другим способом?")
            .setMessage(body).setPositiveButton("Поделиться") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, body) }
                activity.startActivity(Intent.createChooser(intent, "Выберите приложение заметок"))
            }.setNegativeButton("Оставить локально", null).show()
    }

    private companion object {
        const val MAX_CONTACT_ROWS = 5000
    }
}
