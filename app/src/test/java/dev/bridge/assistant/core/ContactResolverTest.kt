package dev.bridge.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactResolverTest {
    @Test fun russianVocativeMatchesOnlyTheExactContactName() {
        val contacts = listOf(
            ContactNumber("Мама", "+7 900 111-22-33"),
            ContactNumber("Мамонтова Анна", "+7 900 111-22-44")
        )
        assertEquals(listOf(contacts[0]), ContactResolver.matching("маме", contacts))
    }

    @Test fun duplicateExactNamesRemainAmbiguous() {
        val contacts = listOf(
            ContactNumber("Мама", "+7 900 111-22-33"),
            ContactNumber("МАМА", "+7 900 111-22-44")
        )
        assertEquals(2, ContactResolver.matching("маме", contacts).size)
    }

    @Test fun malformedOrUnknownNamesDoNotMatch() {
        val contacts = listOf(ContactNumber("Мама", "+7 900 111-22-33"))
        assertTrue(ContactResolver.matching("   ", contacts).isEmpty())
        assertTrue(ContactResolver.matching("мамонт", contacts).isEmpty())
    }
}
