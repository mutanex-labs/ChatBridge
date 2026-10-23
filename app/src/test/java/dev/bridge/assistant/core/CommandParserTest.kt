package dev.bridge.assistant.core

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    @Test fun callsNamedContact() {
        assertEquals(LocalCommand.Call("маме"), CommandParser.parse("Позвони маме"))
        assertEquals(LocalCommand.Call("Мама"), CommandParser.parse("набери контакту Мама"))
    }
    @Test fun opensApplications() {
        assertEquals(LocalCommand.Open("Telegram"), CommandParser.parse("открой Telegram"))
    }
    @Test fun savesNotes() {
        assertEquals(LocalCommand.Note("картошка и горошек"), CommandParser.parse("запиши в заметки картошка и горошек"))
    }
    @Test fun leavesOtherCommandsToModel() {
        assertEquals(LocalCommand.AskGpt, CommandParser.parse("объясни этот экран"))
        assertEquals(LocalCommand.AskGpt, CommandParser.parse(""))
    }
}
