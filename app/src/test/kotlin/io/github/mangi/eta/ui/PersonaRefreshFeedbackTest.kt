package io.github.mangi.eta.ui

import android.content.Context
import io.github.mangi.eta.data.repository.RemotePersonaCache
import io.github.mangi.eta.data.repository.RemotePersonaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "b+zh+Hans")
class PersonaRefreshFeedbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val snapshot = RemotePersonaCache.Snapshot("新内容", "https://gist.github.com/U188/example", "b".repeat(40), 1234L)

    @Test fun changedAndUnchangedStatusesAreDistinctAndVerifiable() {
        val result = RemotePersonaStore.RefreshResult(
            RemotePersonaStore.PersonaResult.Success(snapshot, true),
            RemotePersonaStore.PersonaResult.Success(snapshot, false),
        )
        val summary = PersonaRefreshFeedback.summary(context, result)
        assertTrue(summary.contains("小蝶：已更新并保存"))
        assertTrue(summary.contains("小枫：与核对时服务器内容一致，无变化"))
        val details = PersonaRefreshFeedback.details(context, result)
        assertTrue(details.contains(snapshot.revision))
        assertTrue(details.contains(snapshot.sha256))
        assertTrue(details.contains(snapshot.source))
        assertTrue(details.contains("磁盘回读校验通过"))
        assertTrue(details.contains("上次成功核对"))
    }

    @Test fun partialFailureExplicitlyKeepsOldPersonaAndDoesNotHideOtherSuccess() {
        val result = RemotePersonaStore.RefreshResult(
            RemotePersonaStore.PersonaResult.Failure("HTTP 403", snapshot),
            RemotePersonaStore.PersonaResult.Success(snapshot, true),
        )
        val summary = PersonaRefreshFeedback.summary(context, result)
        assertTrue(summary.contains("小蝶：刷新失败（HTTP 403），未更新"))
        assertTrue(summary.contains("小枫：已更新并保存"))
        assertTrue(PersonaRefreshFeedback.details(context, result).contains("保留原来的人格"))
    }
}
