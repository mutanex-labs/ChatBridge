package dev.bridge.assistant

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class GptClient(private val auth: ChatGptAuth) {
    fun models(): List<Pair<String, String>> {
        val token = auth.accessToken()
        val response = Http.get("https://api.openai.com/v1/models", token)
        val array = response.getJSONArray("models")
        return (0 until array.length()).map { array.getJSONObject(it) }
            .filter { it.optString("visibility") == "list" }
            .map { it.getString("slug") to it.optString("display_name", it.getString("slug")) }
    }

    fun ask(model: String, query: String, screenshot: Bitmap? = null): String {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", query))
        if (screenshot != null) {
            val out = ByteArrayOutputStream()
            val longestSide = maxOf(screenshot.width, screenshot.height)
            val resized = if (longestSide > 1200) {
                val ratio = 1200f / longestSide
                Bitmap.createScaledBitmap(screenshot,
                    (screenshot.width * ratio).toInt().coerceAtLeast(1),
                    (screenshot.height * ratio).toInt().coerceAtLeast(1), true)
            } else screenshot
            try {
                check(resized.compress(Bitmap.CompressFormat.JPEG, 70, out)) { "Could not encode screenshot" }
            } finally {
                if (resized !== screenshot) resized.recycle()
            }
            require(out.size() < 3_000_000) { "Screenshot too large" }
            val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            content.put(JSONObject().put("type", "input_image").put("image_url", dataUrl))
        }
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val connection = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 90000
            connection.setRequestProperty("Authorization", "Bearer ${auth.accessToken()}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val details = connection.errorStream?.bufferedReader()?.use { it.readText().take(350) } ?: ""
                error("Responses API: HTTP ${connection.responseCode} $details")
            }
            val answer = StringBuilder()
            var completed = false
            connection.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ")
                    if (data == "[DONE]") break
                    val event = JSONObject(data)
                    when (event.optString("type")) {
                        "response.output_text.delta" -> {
                            answer.append(event.optString("delta"))
                            require(answer.length <= 1_000_000) { "Response exceeds size limit" }
                        }
                        "response.completed" -> completed = true
                        "response.incomplete" -> error("Model response was incomplete: ${event.toString().take(300)}")
                        "response.failed", "error" -> error("Model returned an error: ${event.toString().take(300)}")
                    }
                }
            }
            require(completed) { "Stream ended without response.completed" }
            return answer.toString()
        } finally { connection.disconnect() }
    }
}
