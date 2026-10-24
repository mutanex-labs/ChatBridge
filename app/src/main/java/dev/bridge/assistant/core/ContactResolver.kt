package dev.bridge.assistant.core

import java.util.Locale

data class ContactNumber(val displayName: String, val number: String)

/** Resolves only exact names and explicit Russian vocative aliases. */
object ContactResolver {
    private val aliases = mapOf(
        "маме" to "мама", "маму" to "мама", "мамой" to "мама", "мам" to "мама",
        "папе" to "папа", "папу" to "папа", "папой" to "папа", "пап" to "папа",
        "бабушке" to "бабушка", "бабушку" to "бабушка", "бабушкой" to "бабушка",
        "дедушке" to "дедушка", "дедушку" to "дедушка", "дедушкой" to "дедушка"
    )

    fun matching(commandName: String, contacts: Iterable<ContactNumber>): List<ContactNumber> {
        val key = normalize(commandName)
        if (key.isBlank()) return emptyList()
        val names = setOf(key, aliases[key]).filterNotNull()
        return contacts.filter { normalize(it.displayName) in names }
            .distinctBy { normalize(it.displayName) to normalizeNumber(it.number) }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun normalizeNumber(value: String): String = value.filter { it.isDigit() || it == '+' }
}
