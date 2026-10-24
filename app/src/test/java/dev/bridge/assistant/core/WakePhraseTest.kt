package dev.bridge.assistant.core

import dev.bridge.assistant.assistant.WakePhrase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun acceptsWakeWord() {
        assertTrue(WakePhrase.matches("open gpt"))
        assertTrue(WakePhrase.matches("Open G P T"))
        assertTrue(WakePhrase.matches("пожалуйста опен джипити"))
    }

    @Test fun rejectsUnrelatedSpeech() {
        assertFalse(WakePhrase.matches("open settings"))
        assertFalse(WakePhrase.matches("gpt models"))
        assertFalse(WakePhrase.matches(""))
        assertFalse(WakePhrase.matches("opening gpt"))
    }
}
