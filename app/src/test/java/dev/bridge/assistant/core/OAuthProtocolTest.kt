package dev.bridge.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthProtocolTest {
    @Test fun firstRegistrationRequiresAnIssuedClientId() {
        assertEquals("oaiapp_123", OAuthProtocol.issuedClientId(null, "oaiapp_123"))
        assertInvalid { OAuthProtocol.issuedClientId(null, null) }
        assertInvalid { OAuthProtocol.issuedClientId(null, OAuthProtocol.DYNAMIC_CLIENT_ID) }
    }

    @Test fun reauthorizationKeepsItsSelectedClientId() {
        assertEquals("oaiapp_saved", OAuthProtocol.issuedClientId("oaiapp_saved", null))
        assertEquals("oaiapp_saved", OAuthProtocol.issuedClientId("oaiapp_saved", "oaiapp_saved"))
        assertInvalid { OAuthProtocol.issuedClientId("oaiapp_saved", "oaiapp_other") }
    }

    @Test fun planUsageRequiresTheGrantedScope() {
        assertTrue(OAuthProtocol.grantsChatGptPlan("openid email chatgpt.tokens.use.direct"))
        assertFalse(OAuthProtocol.grantsChatGptPlan("openid email"))
        assertFalse(OAuthProtocol.grantsChatGptPlan(null))
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid OAuth callback to be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        } catch (_: IllegalStateException) {
            // expected
        }
    }
}
