package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    private val resources = CopyOnWriteArraySet<CancellableResource>()
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled
    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    data class SteeringInput(val text: String, val imagesJson: String = "[]")

    /** [drainChildNoticesOrPollSteeringOrSeal] 的原子收尾结果。 */
    sealed interface SealOutcome {
        /** 本次带走的一批子代理完成通知（合并为一条 user 消息注入）。 */
        data class Notices(val notices: List<String>) : SealOutcome
        /** 本次消费的一条 steering 补充指令。 */
        data class Steering(val input: SteeringInput) : SealOutcome
        /** 有压缩待处理，未封存；上层应先消费压缩队列再回到本边界重试。 */
        data object PendingCompact : SealOutcome
        /** 队列为空，已永久关闭本 run 的接收入口。 */
        data object Sealed : SealOutcome
    }
    private data class PendingSteering(val input: SteeringInput, val immediate: Boolean)
    private val steeringMessages = ArrayDeque<PendingSteering>()
    /**
     * 子代理完成通知的独立送达队列。
     *
     * **刻意不复用 [steeringMessages]**：steering 是用户补充指令通道，其消息会落盘并作为
     * 用户气泡渲染。子代理结果走这里，只作为运行时 `user` 消息注入模型上下文，
     * 不产生 UserSupplementReceived 投影事件，因而既不落盘也不出现在会话 UI。
     */
    private val childNotices = ArrayDeque<String>()
    private var acceptingSteering = true
    private var stoppedSteering = emptyList<SteeringInput>()
    fun takeStoppedSteering(): List<SteeringInput> = lock.withLock {
        stoppedSteering.also { stoppedSteering = emptyList() }
    }
    @Volatile private var paused = false
    @Volatile private var checkpointPaused = false
    private val transportCallbackDepth = ThreadLocal<Int>()
    @Volatile private var pausedInterrupt = false
    private var pendingCompact: CompactRequest? = null
    private var boundaryObserver: (() -> Unit)? = null
    private var taskProgressReporter: ((String) -> Boolean)? = null

    data class CompactRequest(
        val keepRecentMessages: Int? = null,
        val compressModelConfig: AgentModelClient.ModelConfig? = null,
    )

    fun cancel() {
        lock.withLock {
            if (!cancelled) stoppedSteering = steeringMessages.map { it.input }
            cancelled = true
            checkpointPaused = false
            acceptingSteering = false
            steeringMessages.clear()
            childNotices.clear()
            pendingCompact = null
            paused = false
            boundaryObserver = null
            taskProgressReporter = null
            pauseCondition.signalAll()
        }
        resources.forEach { resource -> runCatching { resource.cancel() } }
    }

    /** Existing interactive steering keeps its immediate-interrupt semantics. */
    fun steer(text: String): Boolean = steer(SteeringInput(text))
    fun steer(input: SteeringInput): Boolean {
        val interrupt = enqueueSteering(input) ?: return false
        interruptSteering(interrupt)
        if (!interrupt) resume()
        return true
    }
    /** Child supervision only: never cut an in-flight response to deliver guidance. */
    fun queueBoundaryGuidance(text: String): Boolean = enqueue(input = SteeringInput(text), immediate = false) != null

    internal fun enqueueSteering(input: SteeringInput): Boolean? = enqueue(input, immediate = true)
    private fun enqueue(input: SteeringInput, immediate: Boolean): Boolean? = lock.withLock {
        if (input.text.isBlank() || cancelled || !acceptingSteering || steeringMessages.size >= MAX_PENDING_STEERING) return null
        val normalized = input.copy(text = input.text.trim())
        if (normalized.text.length > MAX_STEERING_CHARS || normalized.imagesJson.length > MAX_STEERING_CHARS) return null
        if (steeringMessages.any { it.input == normalized }) return null
        if (steeringMessages.sumOf { it.input.text.length + it.input.imagesJson.length } + normalized.text.length + normalized.imagesJson.length > MAX_STEERING_TOTAL_CHARS) return null
        steeringMessages.addLast(PendingSteering(normalized, immediate))
        !paused
    }
    internal fun interruptSteering(interrupt: Boolean) { if (interrupt) interruptCurrentRequest() }

    fun requestCompact(keepRecentMessages: Int? = null, compressModelConfig: AgentModelClient.ModelConfig? = null): Boolean {
        lock.withLock {
            if (cancelled || !acceptingSteering) return false
            pendingCompact = CompactRequest(keepRecentMessages, compressModelConfig)
            paused = false
            pauseCondition.signalAll()
        }
        return true
    }
    val hasPendingCompact: Boolean get() = lock.withLock { pendingCompact != null }
    fun takePendingCompact(): CompactRequest? = lock.withLock {
        val request = pendingCompact
        pendingCompact = null
        request
    }
    private fun interruptCurrentRequest() {
        val interruptibles = resources.filter { it.interruptible }
        if (paused && interruptibles.isNotEmpty()) pausedInterrupt = true
        interruptibles.forEach { resource -> runCatching { resource.cancel() } }
    }
    fun pollSteeringMessage(): String? = pollSteeringInput()?.text

    fun pollSteeringInput(): SteeringInput? = lock.withLock { steeringMessages.pollFirst()?.input }

    /**
     * 只取当前 steering 队首（不封存），供正文回合结束后逐条注入。
     */
    fun pollSteeringOrSeal(): String? = pollSteeringInputOrSeal()?.text

    fun pollSteeringInputOrSeal(): SteeringInput? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it.input }
            if (pendingCompact != null) return null
            childNotices.clear()
            acceptingSteering = false
            null
        }
    /**
     * 自然结束边界的**原子**收尾：在同一把锁内依次决定注入子代理通知、注入 steering，
     * 或封存本 run 的接收入口。
     *
     * 这解决了「先排空通知、再单独封存」两段加锁之间的竞态：
     * 若子任务在两步之间完成，[enqueueChildNotice] 会看到 `acceptingSteering` 仍为 true 从而计数成功，
     * 但紧接着的 `childNotices.clear()` 会把它清掉——通知被计数却从未注入。合并为单次持锁后，
     * 完成通知要么在本方法取走通知**之前**入队（于是被本次 [SealOutcome.Notices] 带走），
     * 要么在封存**之后**到达（此时 `acceptingSteering` 已为 false，[enqueueChildNotice] 返回 false 不再计数）。
     */
    fun drainChildNoticesOrPollSteeringOrSeal(): SealOutcome =
        lock.withLock {
            // 与既有顺序一致：子代理通知优先于 steering。
            val notices = childNotices.toList()
            if (notices.isNotEmpty()) {
                childNotices.clear()
                return SealOutcome.Notices(notices)
            }
            steeringMessages.pollFirst()?.let { return SealOutcome.Steering(it.input) }
            // 压缩待处理时不封存：交回上层先消费压缩队列，再回到本边界重试。
            if (pendingCompact != null) return SealOutcome.PendingCompact
            acceptingSteering = false
            SealOutcome.Sealed
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 子代理完成通知入队。
     *
     * @return false 表示本 run 已停止接收（已取消或已封存）；调用方无需重试，也不应据此报错。
     */
    fun enqueueChildNotice(notice: String): Boolean = lock.withLock {
        if (notice.isBlank() || cancelled || !acceptingSteering) return false
        childNotices.addLast(notice.trim())
        true
    }

    /** 取出当前全部待注入通知：同一轮合并为一条 user 消息，避免打爆 prompt 缓存。 */
    fun drainChildNotices(): List<String> = lock.withLock {
        val drained = childNotices.toList()
        childNotices.clear()
        drained
    }

    val hasPendingChildNotice: Boolean
        get() = lock.withLock { childNotices.isNotEmpty() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    val isPaused: Boolean
        get() = paused

    val hasPausedInterrupt: Boolean
        get() = pausedInterrupt

    /** Only interactive steering may terminate an in-flight provider request. */
    val hasPendingImmediateSteering: Boolean get() = lock.withLock { steeringMessages.any { it.immediate } }
    fun consumePausedInterrupt(): Boolean = lock.withLock {
        val value = pausedInterrupt
        pausedInterrupt = false
        value
    }

    fun setPauseBoundaryObserver(observer: (() -> Unit)?) { lock.withLock { boundaryObserver = observer } }
    fun setTaskProgressReporter(reporter: ((String) -> Boolean)?) { lock.withLock { taskProgressReporter = reporter } }
    fun reportTaskProgress(summary: String): Boolean {
        if (summary.isBlank() || summary.length > 1000) return false
        val reporter = lock.withLock { if (cancelled) null else taskProgressReporter }
        return reporter?.invoke(summary.trim()) ?: false
    }

    /** Legacy cooperative-only pause; supervised children use pause() to bound stalled SSE. */
    fun pauseAtCheckpoint() { lock.withLock { if (!cancelled) checkpointPaused = true } }
    fun pause() {
        lock.withLock { if (!cancelled) paused = true }
        interruptCurrentRequest()
    }
    fun resume() {
        lock.withLock {
            checkpointPaused = false
            paused = false
            pauseCondition.signalAll()
        }
    }

    fun throwIfCancelled() {
        if ((transportCallbackDepth.get() ?: 0) > 0) {
            if (cancelled) throw AgentRunCancelledException()
            return
        }
        val observer = lock.withLock { if ((paused || checkpointPaused) && !cancelled) boundaryObserver else null }
        if (observer != null) runCatching { observer() }
        lock.withLock {
            while ((paused || checkpointPaused) && !cancelled) {
                try { pauseCondition.await() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); cancelled = true }
            }
        }
        if (cancelled) throw AgentRunCancelledException()
    }

    internal fun <T> withTransportCallback(block: () -> T): T {
        val depth = transportCallbackDepth.get() ?: 0
        transportCallbackDepth.set(depth + 1)
        try { return block() }
        finally { if (depth == 0) transportCallbackDepth.remove() else transportCallbackDepth.set(depth) }
    }
    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try { cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw AgentRunCancelledException() }
        finally { binding.close() }
        throwIfCancelled()
    }
    fun register(interruptible: Boolean = false, cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel, interruptible)
        resources.add(resource)
        if (cancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource) }
    }
    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() { closeBlock() }
    }
    private class CancellableResource(private val cancelBlock: () -> Unit, val interruptible: Boolean) {
        private val cancelled = AtomicBoolean(false)
        fun cancel() { if (cancelled.compareAndSet(false, true)) cancelBlock() }
    }
    private companion object {
        const val MAX_PENDING_STEERING = 16
        const val MAX_STEERING_CHARS = 4000
        const val MAX_STEERING_TOTAL_CHARS = 16000
    }
}

internal class AgentRunCancelledException(
    val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    val reasoningContent: String = "",
) : RuntimeException("Agent run cancelled")
