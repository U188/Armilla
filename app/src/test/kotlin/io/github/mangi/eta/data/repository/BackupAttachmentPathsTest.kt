package io.github.mangi.eta.data.repository

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupAttachmentPathsTest {
    @Test fun onlyOwnedAttachmentRootsRelocate() {
        val own = "/data/user/10/io.github.mangi.eta/cache/eta-chat-images/c/a.jpg"
        assertEquals("/new/images/c/a.jpg", BackupAttachmentPaths.relocate(own, "io.github.mangi.eta", File("/new/images"), File("/new/imports")))
        listOf("/data/user/0/com.tencent.mm/files/db", "/data/user/0/io.github.mangi.eta/files/other.txt", "/data/user/0/io.github.mangi.eta/cache/eta-chat-images/../secret").forEach { path ->
            assertEquals(path, BackupAttachmentPaths.relocate(path, "io.github.mangi.eta", File("/new/images"), File("/new/imports")))
        }
    }

    @Test fun proseAndToolArgumentsRemainUntouchedButNestedAttachmentJsonMoves() {
        val original = JSONObject().put("content", "old/path is a code example")
            .put("arguments", "old/path")
            .put("contentJson", JSONArray().put(JSONObject().put("type", "image_file").put("path", "old/path")).toString())
        val result = BackupAttachmentPaths.transform(original, { it.replace("old/", "new/") }) as JSONObject
        assertEquals(original.getString("content"), result.getString("content"))
        assertEquals("old/path", result.getString("arguments"))
        assertEquals("new/path", JSONArray(result.getString("contentJson")).getJSONObject(0).getString("path"))
    }
}
