package io.github.mangi.eta.agent.voice.doubao

import android.app.Application
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceManagementTest {
    @Test fun deletionPersistsIsAccountScopedAndIgnoresLateUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "personal-voices.json")
        file.writeText(JSONArray().put(JSONObject().put("id", "S_same").put("name", "a").put("account", "account-a"))
            .put(JSONObject().put("id", "S_same").put("name", "b").put("account", "account-b")).toString())
        PersonalVoices.load(context)
        val removed = PersonalVoices.state.value.first { it.account == "account-a" }
        PersonalVoices.removeLocal(removed)
        PersonalVoices.accept(removed)
        assertEquals(listOf("account-b"), PersonalVoices.state.value.map { it.account })
        val stored = JSONArray(file.readText())
        assertEquals(1, stored.length())
        assertEquals("account-b", stored.getJSONObject(0).getString("account"))
    }
    @Test fun catalogErrorsRetainBusinessReasonButRedactBothCredentials() {
        val json = JSONObject().put("ResponseMetadata", JSONObject().put("RequestId", "request-123")
            .put("Error", JSONObject().put("Code", "SignatureDoesNotMatch").put("Message", "invalid test-ak and test-sk")))
        val error = DoubaoVoiceCatalog.responseError(401, json, listOf("test-ak", "test-sk"))!!
        assertTrue(error.contains("SignatureDoesNotMatch"))
        assertTrue(error.contains("签名校验失败"))
        assertFalse(error.contains("test-ak"))
        assertFalse(error.contains("test-sk"))
        assertTrue(error.contains("request-123"))
        assertNotNull(DoubaoVoiceCatalog.responseError(401, JSONObject(), emptyList()))
        assertNull(DoubaoVoiceCatalog.responseError(200, JSONObject().put("Result", JSONObject()), emptyList()))
    }
}
