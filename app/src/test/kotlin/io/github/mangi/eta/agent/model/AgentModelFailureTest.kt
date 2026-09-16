package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelFailureTest {
    @Test
    fun extractsGoogleValidationUrlFrom403() {
        val body = """{"error":{"code":403,"message":"Verify your account to continue.","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"VALIDATION_REQUIRED","metadata":{"validation_url":"https://accounts.google.com/signin/continue?sarp=1&plt=token"}}]}}"""
        val failure = AgentModelFailure.http(403, body)
        assertTrue(failure.message!!.contains("accounts.google.com/signin/continue"))
        assertTrue(failure.message!!.contains("无痕"))
        assertEquals(
            "https://accounts.google.com/signin/continue?sarp=1&plt=token",
            AgentModelFailure.extractGoogleValidationUrl(
                org.json.JSONObject(body).getJSONObject("error"),
                body,
            ),
        )
    }
}
