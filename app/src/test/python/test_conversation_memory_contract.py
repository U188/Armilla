"""Non-compiling guards for the lazy-conversation persistence boundary."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"

class ConversationMemoryContractTest(unittest.TestCase):
    def test_app_loads_selected_content_only(self):
        text = (SRC / "ui/app/AgentAppState.kt").read_text()
        self.assertGreaterEqual(text.count("AgentConversationStore.load(appContext, selectedOnly = true)"), 2)
        self.assertNotIn("private val initialConversations", text)

    def test_placeholder_is_an_explicit_state_not_empty_messages(self):
        text = (SRC / "ui/model/AgentChatUiState.kt").read_text()
        self.assertIn("val conversationContentLoaded: Boolean = true", text)
        store = (SRC / "ui/app/AgentConversationStore.kt").read_text()
        self.assertIn("conversationContentLoaded = withContent", store)

    def test_unloaded_content_is_not_replaced(self):
        text = (SRC / "ui/app/AgentConversationStore.kt").read_text()
        save = text[text.index("suspend fun save("):text.index("fun searchStoredConversation(")]
        self.assertIn("if (!state.conversationContentLoaded) continue", save)
        self.assertLess(save.index("if (!state.conversationContentLoaded) continue"), save.index("dao.deleteMessagesForConversation"))
        self.assertNotIn("dao.deleteMessages()", save)
        self.assertNotIn("dao.insertConversations(", save)

    def test_parent_metadata_never_uses_replace(self):
        dao = (SRC / "data/db/ConversationDao.kt").read_text()
        self.assertIn("@Update(entity = ConversationEntity::class)", dao)
        self.assertIn("suspend fun updateConversationMetadata", dao)
        self.assertIn("@Insert(onConflict = OnConflictStrategy.IGNORE)", dao)

    def test_clean_content_eviction_follows_commit(self):
        text = (SRC / "ui/app/AgentAppState.kt").read_text()
        self.assertIn("saved[id] === state", text)
        self.assertIn("state.pendingImages.isNotEmpty()", text)
        self.assertIn("state.isPaused", text)
        self.assertIn("releasePersistedConversationContent(conversations)", text)

    def test_large_heap_is_app_manifest_policy(self):
        app = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        self.assertEqual("true", app.get("{http://schemas.android.com/apk/res/android}largeHeap"))

if __name__ == "__main__":
    unittest.main()
