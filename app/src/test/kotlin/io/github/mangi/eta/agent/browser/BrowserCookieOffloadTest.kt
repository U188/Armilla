package io.github.mangi.eta.agent.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCookieOffloadTest {
    @Test
    fun envNamesMatchMinisHubSkills() {
        assertEquals("COOKIE_SESSDATA", BrowserCookieOffload.envName("SESSDATA"))
        assertEquals("COOKIE_BILI_JCT", BrowserCookieOffload.envName("bili_jct"))
        assertEquals("COOKIE_DEDEUSERID", BrowserCookieOffload.envName("DedeUserID"))
        assertEquals("COOKIE_AUTH_TOKEN", BrowserCookieOffload.envName("auth_token"))
        assertEquals("COOKIE_CT0", BrowserCookieOffload.envName("ct0"))
    }

    @Test
    fun scriptQuotesValuesAndOmitsThemFromFileName() {
        val script = BrowserCookieOffload.script(
            url = "https://www.bilibili.com/",
            cookies = listOf("SESSDATA" to "a'b", "bili_jct" to "token"),
        )
        val sessdata = script.lineSequence().first { it.startsWith("export COOKIE_SESSDATA=") }
        assertTrue(sessdata.startsWith("export COOKIE_SESSDATA='"))
        assertTrue(sessdata.endsWith("'"))
        assertFalse(sessdata.contains("=a'b"))
        assertTrue(sessdata.contains("a"))
        assertTrue(sessdata.contains("b"))
        assertTrue(script.contains("export COOKIE_BILI_JCT='token'"))
        assertEquals(
            "env_cookies_www_bilibili_com_1.sh",
            BrowserCookieOffload.fileName("www.bilibili.com", 1L),
        )
    }

    @Test
    fun fuzzyRequiresEveryKeyword() {
        assertTrue(BrowserCookieOffload.matches("SESSDATA", listOf("sess"), fuzzy = true))
        assertFalse(BrowserCookieOffload.matches("SESSDATA", listOf("sess", "jct"), fuzzy = true))
        assertTrue(BrowserCookieOffload.matches("SESSDATA", listOf("SESSDATA"), fuzzy = false))
        assertFalse(BrowserCookieOffload.matches("SESSDATA", listOf("sess"), fuzzy = false))
    }

    @Test
    fun keywordsAcceptArrayOrSeparatedString() {
        assertEquals(
            listOf("SESSDATA", "bili_jct"),
            BrowserCookieOffload.keywords(JSONObject().put("keywords", org.json.JSONArray(listOf("SESSDATA", "bili_jct")))),
        )
        assertEquals(
            listOf("auth_token", "ct0"),
            BrowserCookieOffload.keywords(JSONObject().put("keywords", "auth_token ct0")),
        )
    }
}
