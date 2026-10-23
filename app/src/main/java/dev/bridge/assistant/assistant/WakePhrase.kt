package dev.bridge.assistant.assistant

import java.util.Locale

object WakePhrase {
    private val candidates = setOf("open gpt", "open g p t", "open chat gpt", "оупен джипити", "опен джипити")
    fun matches(heard: String): Boolean {
        val text = heard.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return candidates.any { candidate -> text == candidate || text.endsWith(" $candidate") }
    }
}

