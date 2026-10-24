package dev.bridge.assistant.core

object OAuthProtocol {
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val CHATGPT_PLAN_SCOPE = "chatgpt.tokens.use.direct"

    /** Resolves a callback without allowing it to replace a registration selected for reauthorization. */
    fun issuedClientId(pendingClientId: String?, callbackClientId: String?): String {
        val returned = callbackClientId?.trim()?.takeIf { it.isNotEmpty() }
        if (pendingClientId != null) {
            require(pendingClientId.isNotBlank() && pendingClientId != DYNAMIC_CLIENT_ID) {
                "Invalid pending OAuth client_id"
            }
            require(returned == null || returned == pendingClientId) {
                "OAuth callback client_id did not match the selected registration"
            }
            return pendingClientId
        }
        return returned?.takeIf { it != DYNAMIC_CLIENT_ID }
            ?: error("Dynamic registration did not return an issued client_id")
    }

    fun grantsChatGptPlan(scope: String?): Boolean =
        scope.orEmpty().split(Regex("\\s+")).any { it == CHATGPT_PLAN_SCOPE }
}
