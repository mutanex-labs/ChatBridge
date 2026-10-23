package dev.bridge.assistant.assistant

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object WakeModelInstaller {
    private const val MAX_UNPACKED_BYTES = 220L * 1024 * 1024
    fun modelDir(context: Context) = File(context.filesDir, "wake-model")
    fun isInstalled(context: Context) = File(modelDir(context), "am/final.mdl").isFile &&
        File(modelDir(context), "conf").isDirectory && File(modelDir(context), "graph").isDirectory

    fun install(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Model archive not readable")
        input.use { installStream(context, it) }
    }

    fun downloadSmallEnglish(context: Context) {
        val archive = File(context.cacheDir, "vosk-model-small-en-us-0.15.zip")
        val connection = URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            connection.inputStream.use { source ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        downloaded += n
                        check(downloaded <= 65L * 1024 * 1024) { "Model download exceeds size limit" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(hash == "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498") { "Model SHA-256 mismatch" }
            archive.inputStream().use { installStream(context, it) }
        } finally {
            connection.disconnect()
            archive.delete()
        }
    }

    private fun installStream(context: Context, input: InputStream) {
        val target = modelDir(context)
        val staging = File(context.filesDir, "wake-model-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create temporary model directory" }
        try {
            input.let { source ->
                ZipInputStream(source.buffered()).use { archive ->
                    var count = 0
                    var expanded = 0L
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        check(++count <= 500) { "Too many model archive entries" }
                        val parts = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                        check(parts.none { it == ".." || it == "." }) { "Unsafe archive path" }
                        val relative = if (parts.firstOrNull()?.startsWith("vosk-model-") == true) parts.drop(1) else parts
                        if (relative.isEmpty()) continue
                        val file = File(staging, relative.joinToString("/"))
                        check(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Archive path escaped model directory" }
                        if (entry.isDirectory) {
                            check(file.mkdirs() || file.isDirectory)
                        } else {
                            check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                            file.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val n = archive.read(buffer)
                                    if (n < 0) break
                                    expanded += n
                                    check(expanded <= MAX_UNPACKED_BYTES) { "Model archive exceeds space limit" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        archive.closeEntry()
                    }
                }
            }
            check(File(staging, "am/final.mdl").isFile && File(staging, "conf").isDirectory && File(staging, "graph").isDirectory) {
                "Not a Vosk speech model archive"
            }
            val previous = File(context.filesDir, "wake-model-previous")
            previous.deleteRecursively()
            if (target.exists()) check(target.renameTo(previous)) { "Cannot stage existing model" }
            if (!staging.renameTo(target)) {
                if (previous.exists()) previous.renameTo(target)
                error("Cannot install speech model")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }
}
