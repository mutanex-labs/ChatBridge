package dev.bridge.assistant.core

sealed interface LocalCommand {
    data class Call(val contact: String): LocalCommand
    data class Open(val application: String): LocalCommand
    data class Note(val body: String): LocalCommand
    data object AskGpt: LocalCommand
}

object CommandParser {
    private val callPattern = Regex("^(?:позвони|набери|позвонить)\\s+(?:контакту\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val openPattern = Regex("^(?:открой|запусти|открыть)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val notePattern = Regex("^(?:запиши|добавь|сохрани)\\s+(?:в\\s+)?заметки?[:\\s]+(.+)$", RegexOption.IGNORE_CASE)

    fun parse(command: String): LocalCommand {
        val text = command.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return LocalCommand.AskGpt
        callPattern.matchEntire(text)?.let { return LocalCommand.Call(it.groupValues[1].trim()) }
        openPattern.matchEntire(text)?.let { return LocalCommand.Open(it.groupValues[1].trim()) }
        notePattern.matchEntire(text)?.let { return LocalCommand.Note(it.groupValues[1].trim()) }
        return LocalCommand.AskGpt
    }
}
