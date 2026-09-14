package io.github.mangi.eta.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class BrowserSessionSnapshot(
    val available: Boolean = false,
    val url: String = "",
    val displayUrl: String = "",
    val host: String = "",
    val title: String = "",
    val isLoading: Boolean = false,
    val isPageVisible: Boolean = false,
    val hasCommittedPage: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val isUserControlling: Boolean = false,
    val lastAgentRunId: String? = null,
    val lastAgentToolCallId: String? = null,
    val desktopMode: Boolean = true,
    val userAgent: String = BrowserUserAgent.DEFAULT.wireName,
)

internal data class BrowserImage(
    val dataUrl: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
)

internal data class BrowserToolResult(
    val content: String,
    val images: List<BrowserImage> = emptyList(),
)

/**
 * Eta 的共享 Agent 浏览器。
 *
 * WebView 可以离屏工作，也可以临时挂到 App 的浏览器页面供用户接管。
 */
// 共享 WebView 必须跨工具调用存活；Activity 容器只在浏览器页面可见时持有，并在 dispose 时解绑。
@SuppressLint("StaticFieldLeak")
internal object AgentBrowserSession {
    private const val TOOL_NAME = "browser_use"
    private const val DEFAULT_TEXT_CHARS = 8_000
    private const val MAX_TEXT_CHARS = 12_000
    private const val NAVIGATION_TIMEOUT_MS = 25_000L
    private const val JAVASCRIPT_TIMEOUT_MS = 8_000L
    private const val ASYNC_JAVASCRIPT_TIMEOUT_MS = 15_000L
    private const val POST_ACTION_TIMEOUT_MS = 10_000L
    private const val SCREENSHOT_MAX_WIDTH = 1_280
    private const val SCREENSHOT_MAX_HEIGHT = 2_400
    private const val SCREENSHOT_QUALITY = 75
    private const val PREVIEW_MAX_WIDTH = 480
    private const val PREVIEW_MAX_HEIGHT = 900
    private const val PREVIEW_QUALITY = 60
    private const val FULL_PAGE_MAX_HEIGHT = 8_192
    private const val FETCH_MAX_BYTES = 8 * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationLock = ReentrantLock()
    private val interrupted = AtomicBoolean(false)
    private val operationEpoch = AtomicLong(0L)
    private val navigationGeneration = AtomicLong(0L)

    private val mutableSnapshots = MutableStateFlow(BrowserSessionSnapshot())
    val snapshots: StateFlow<BrowserSessionSnapshot> = mutableSnapshots.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var contextWrapper: MutableContextWrapper? = null

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var attachedContainer: ViewGroup? = null

    @Volatile
    private var currentLoadWaiter: LoadWaiter? = null

    @Volatile
    private var currentUrl: String = ""

    @Volatile
    private var currentHost: String = ""

    @Volatile
    private var currentTitle: String = ""

    @Volatile
    private var currentError: String? = null

    @Volatile
    private var currentHttpStatus: Int? = null

    @Volatile
    private var currentProgress: Int = 0

    @Volatile
    private var currentLoading: Boolean = false

    @Volatile
    private var currentPageVisible: Boolean = false

    @Volatile
    private var committedMainFrameUrl: String = ""

    @Volatile
    private var userControlActive: Boolean = false

    @Volatile
    private var activeActionIsUserInitiated: Boolean = false

    @Volatile
    private var activeOperationEpoch: Long = 0L

    @Volatile
    private var lastAgentToolCallId: String? = null

    @Volatile
    private var lastAgentRunId: String? = null

    @Volatile
    private var activeAgentRunId: String? = null

    @Volatile
    private var userAgentProfile: BrowserUserAgent = BrowserUserAgent.DEFAULT

    @Volatile
    private var sessionViewport: Pair<Int, Int>? = null

    @Volatile
    private var pendingAsyncJs: CompletableFuture<String>? = null

    private val pageBridge = PageBridge()

    fun initialize(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) {
                    appContext = context.applicationContext
                    userAgentProfile = BrowserUserAgent.load()
                }
            }
        }
    }

    fun execute(
        context: Context,
        args: JSONObject,
        runId: String,
        toolCallId: String,
    ): BrowserToolResult {
        val result = executeInternal(
            context = context,
            args = args,
            userInitiated = false,
            agentRunId = runId,
        )
        val succeeded = runCatching { JSONObject(result.content).optBoolean("ok", false) }
            .getOrDefault(false)
        if (succeeded && toolCallId.isNotBlank()) {
            runCatching {
                callOnMain {
                    lastAgentRunId = runId.takeIf(String::isNotBlank)
                    lastAgentToolCallId = toolCallId
                    publishSnapshotOnMain()
                }
            }
        }
        return result
    }

    fun navigateFromUser(context: Context, url: String): BrowserToolResult {
        initialize(context)
        val target = BrowserNavigation.normalize(url.trim()) { path ->
            LinuxGuestPathResolver.resolveForApp(context.applicationContext, path)
        }
        return executeInternal(
            context = context,
            args = JSONObject().put("action", "navigate").put("url", target),
            userInitiated = true,
        )
    }

    fun setDesktopModeFromUser(enabled: Boolean): BrowserToolResult {
        val context = appContext
            ?: return errorResult("set_user_agent", "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        val profile = if (enabled) BrowserUserAgent.DESKTOP_CHROME else BrowserUserAgent.MOBILE_CHROME
        return executeInternal(
            context = context,
            args = JSONObject().put("action", "set_user_agent").put("user_agent", profile.wireName),
            userInitiated = true,
        )
    }

    fun goBackFromUser(): BrowserToolResult =
        executeFromExistingContext("go_back", userInitiated = true)

    fun goForwardFromUser(): BrowserToolResult =
        executeFromExistingContext("go_forward", userInitiated = true)

    fun reloadFromUser(): BrowserToolResult =
        executeFromExistingContext("reload", userInitiated = true)

    /** 停止必须能越过串行操作锁，才能立刻唤醒正在等待导航的工具调用。 */
    fun stopFromUser(): BrowserToolResult {
        interruptCurrentAction(force = true)
        return toolResult(baseEnvelope("stop", ok = true, status = "ok"))
    }

    fun resetFromUser(): BrowserToolResult {
        val context = appContext
            ?: return errorResult("reset", "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        return operationLock.withLock {
            interrupted.set(true)
            currentLoadWaiter?.complete(LoadOutcome(false, "CANCELLED", "操作已取消"))
            callOnMain {
                destroyWebViewOnMain()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                clearSessionStateOnMain()
            }
            initialize(context)
            toolResult(baseEnvelope("reset", ok = true, status = "ok"))
        }
    }

    fun interruptAgentAction(runId: String? = null) {
        if (userControlActive) return
        if (!runId.isNullOrBlank() && activeAgentRunId != runId) return
        interruptCurrentAction(force = false)
    }

    private fun interruptCurrentAction(force: Boolean) {
        if (!force && activeActionIsUserInitiated) return
        interrupted.set(true)
        operationEpoch.incrementAndGet()
        activeOperationEpoch = 0L
        navigationGeneration.incrementAndGet()
        currentLoadWaiter?.complete(LoadOutcome(false, "CANCELLED", "操作已取消"))
        mainHandler.post {
            runCatching { webView?.stopLoading() }
            currentLoading = false
            currentPageVisible = committedMainFrameUrl.isNotBlank()
            publishSnapshotOnMain()
        }
    }

    fun attachTo(container: ViewGroup, hostContext: Context) {
        initialize(hostContext)
        val wasAlreadyControlling = userControlActive
        userControlActive = true
        if (!wasAlreadyControlling) interruptCurrentAction(force = true)
        runOnMain {
            attachedContainer?.takeIf { it !== container }?.removeAllViews()
            attachedContainer = container
            contextWrapper?.baseContext = hostContext
            webView?.let { attachWebViewOnMain(it, container) }
            publishSnapshotOnMain()
        }
    }

    fun detachFrom(container: ViewGroup) {
        runOnMain {
            if (attachedContainer === container) {
                val wasControlling = userControlActive
                userControlActive = false
                if (wasControlling) interruptCurrentAction(force = true)
                webView?.takeIf { it.parent === container }?.let(container::removeView)
                attachedContainer = null
                appContext?.let { contextWrapper?.baseContext = it }
                publishSnapshotOnMain()
            }
        }
    }

    /**
     * 聊天页工具卡片的实时预览截图。
     *
     * 只在主线程绘制当前视口，不占用串行操作锁、不中断 Agent 或用户操作；
     * 页面不存在或绘制失败时返回 null，由调用方显示占位。
     */
    fun capturePreview(): BrowserImage? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val view = webView ?: return null
        if (currentUrl.isBlank()) return null
        return runCatching {
            val captured = captureViewport(
                view,
                maxWidth = PREVIEW_MAX_WIDTH,
                maxHeight = PREVIEW_MAX_HEIGHT,
                quality = PREVIEW_QUALITY,
            )
            BrowserImage(
                dataUrl = "data:image/jpeg;base64," +
                    Base64.encodeToString(captured.bytes, Base64.NO_WRAP),
                mimeType = "image/jpeg",
                bytes = captured.bytes.size,
                width = captured.width,
                height = captured.height,
            )
        }.getOrNull()
    }

    private fun executeFromExistingContext(action: String, userInitiated: Boolean): BrowserToolResult {
        val context = appContext
            ?: return errorResult(action, "BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        return executeInternal(
            context = context,
            args = JSONObject().put("action", action),
            userInitiated = userInitiated,
        )
    }

    private fun executeInternal(
        context: Context,
        args: JSONObject,
        userInitiated: Boolean,
        agentRunId: String? = null,
    ): BrowserToolResult {
        initialize(context)
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        if (action !in SUPPORTED_ACTIONS) {
            return errorResult(action.ifBlank { "unknown" }, "INVALID_ACTION", "浏览器 action 无效或缺失")
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return errorResult(action, "MAIN_THREAD_CALL", "浏览器操作不能阻塞主线程")
        }

        return operationLock.withLock {
            if (!userInitiated && userControlActive) {
                return@withLock errorResult(
                    action = action,
                    code = "USER_CONTROL_ACTIVE",
                    message = "用户正在接管浏览器，请等待用户离开浏览器页面后再继续",
                    status = "blocked",
                )
            }
            interrupted.set(false)
            val epoch = operationEpoch.incrementAndGet()
            activeOperationEpoch = epoch
            activeActionIsUserInitiated = userInitiated
            activeAgentRunId = agentRunId.takeUnless { userInitiated }
            callOnMain {
                currentError = null
                publishSnapshotOnMain()
            }
            try {
                runCatching {
                    val result = when (action) {
                        "navigate" -> navigate(args)
                        "get_readable" -> readPage(args, readable = true)
                        "get_text" -> readPage(args, readable = false)
                        "find_elements" -> findElements(args)
                        "click" -> click(args)
                        "type" -> type(args)
                        "scroll" -> scroll(args)
                        "screenshot" -> screenshot(args)
                        "get_page_info" -> pageInfo()
                        "go_back" -> historyNavigation(action, backwards = true)
                        "go_forward" -> historyNavigation(action, backwards = false)
                        "reload" -> reload()
                        "wait_for_selector" -> waitForSelector(args)
                        "execute_js" -> executeJs(args)
                        "get_backbone" -> getBackbone(args)
                        "hover" -> hover(args)
                        "fetch" -> fetchResource(args)
                        "get_cookies" -> getCookies(args)
                        "set_cookies" -> setCookies(args)
                        "set_user_agent" -> setUserAgent(args)
                        "set_viewport" -> setViewport(args)
                        "scroll_and_collect" -> scrollAndCollect(args)
                        "wait_for_dom_stable" -> waitForDomStable(args)
                        else -> throw BrowserFailure("INVALID_ACTION", "浏览器 action 无效")
                    }
                    attachVisualSnapshot(action, result, userInitiated)
                }.getOrElse { throwable -> failureResult(action, throwable) }
            } finally {
                if (activeOperationEpoch == epoch) activeOperationEpoch = 0L
                activeActionIsUserInitiated = false
                if (activeAgentRunId == agentRunId) activeAgentRunId = null
            }
        }
    }

    private fun navigate(args: JSONObject): BrowserToolResult {
        val requested = args.optString("url").trim()
        if (requested.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "navigate 缺少 url")
        val rawUrl = BrowserNavigation.normalize(requested) { path ->
            val context = appContext ?: return@normalize path
            LinuxGuestPathResolver.resolveForApp(context, path)
        }
        val view = ensureWebView()
        val epoch = activeOperationEpoch
        val waiter = LoadWaiter()
        currentLoadWaiter = waiter
        val timeout = args.optLong("timeout_ms", NAVIGATION_TIMEOUT_MS)
            .coerceIn(500L, NAVIGATION_TIMEOUT_MS)
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) {
                throw BrowserFailure("NAVIGATION_SUPERSEDED", "页面导航已被新的操作替代", "cancelled")
            }
            currentError = null
            currentHttpStatus = null
            currentUrl = rawUrl
            currentHost = hostOf(rawUrl)
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            view.loadUrl(rawUrl)
        }

        val outcome = try {
            waiter.await(timeout) ?: run {
                navigationGeneration.incrementAndGet()
                callOnMain {
                    view.stopLoading()
                    currentLoading = false
                    currentPageVisible = committedMainFrameUrl.isNotBlank()
                    currentError = "页面加载超时"
                    publishSnapshotOnMain()
                }
                throw BrowserFailure("NAVIGATION_TIMEOUT", "页面加载超时", status = "timeout")
            }
        } finally {
            if (currentLoadWaiter === waiter) currentLoadWaiter = null
        }
        if (!outcome.ok) throw BrowserFailure(outcome.code, outcome.message)
        throwIfInterrupted()

        currentHttpStatus?.takeIf { it >= 400 }?.let { code ->
            throw BrowserFailure("HTTP_$code", "网页返回 HTTP $code")
        }
        return toolResult(
            baseEnvelope("navigate", ok = true, status = "ok")
                .put("redirected", rawUrl != currentUrl)
        )
    }

    private fun readPage(args: JSONObject, readable: Boolean): BrowserToolResult {
        val view = requirePage()
        val offset = args.optInt("offset", 0).coerceIn(0, 200_000)
        val maxChars = args.optInt("max_chars", DEFAULT_TEXT_CHARS)
            .coerceIn(256, MAX_TEXT_CHARS)
        val selector = if (readable) null else validatedSelector(args, required = false)
        val value = evaluateObject(
            view,
            if (readable) {
                BrowserDomScripts.readable(offset, maxChars)
            } else {
                BrowserDomScripts.text(selector, offset, maxChars)
            }
        )
        val action = if (readable) "get_readable" else "get_text"
        return toolResult(
            mergeValue(baseEnvelope(action, true, "ok"), value)
                .put("content_format", if (readable) "markdown" else "text")
        )
    }

    private fun findElements(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(args, required = false)
        val value = evaluateObject(view, BrowserDomScripts.findElements(selector))
        return toolResult(
            mergeValue(baseEnvelope("find_elements", true, "ok"), value)
        )
    }

    private fun click(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val target = targetFrom(args)
        val value = evaluateObject(view, BrowserDomScripts.click(target.selector, target.x, target.y))
        waitForPostAction()
        return toolResult(
            mergeValue(baseEnvelope("click", true, "ok"), value)
                .put("side_effect", "possible")
        )
    }

    private fun type(args: JSONObject): BrowserToolResult {
        if (!args.has("text") || args.isNull("text")) {
            throw BrowserFailure("INVALID_ARGUMENT", "type 缺少 text")
        }
        val inputText = args.optString("text")
        val submit = args.optBoolean("submit", false)
        val view = requirePage()
        val target = targetFrom(args)
        val value = evaluateObject(
            view,
            BrowserDomScripts.type(
                selector = target.selector,
                x = target.x,
                y = target.y,
                text = inputText,
                submit = submit,
            )
        )
        waitForPostAction()
        return toolResult(
            mergeValue(baseEnvelope("type", true, "ok"), value)
                .put("side_effect", if (submit) "possible" else "local_input")
        )
    }

    private fun scroll(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val direction = args.optString("direction", "down").lowercase(Locale.ROOT)
            .takeIf { it == "up" || it == "down" }
            ?: throw BrowserFailure("INVALID_ARGUMENT", "direction 仅支持 up 或 down")
        val amount = args.optInt("amount", 600).coerceIn(1, 5_000)
        val selector = validatedSelector(args, required = false)
        val value = evaluateObject(view, BrowserDomScripts.scroll(selector, direction, amount))
        Thread.sleep(200)
        return toolResult(mergeValue(baseEnvelope("scroll", true, "ok"), value))
    }

    private fun screenshot(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val fullPage = args.optBoolean("full_page", false)
        val captured = if (fullPage) captureFullPage(view) else captureViewport(view)
        val includeImage = args.optBoolean("read_image", true)
        val envelope = baseEnvelope("screenshot", true, "ok")
            .put("image_width", captured.width)
            .put("image_height", captured.height)
            .put("image_bytes", captured.bytes.size)
            .put("full_page", fullPage)
        val image = if (includeImage) {
            BrowserImage(
                dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(captured.bytes, Base64.NO_WRAP),
                mimeType = "image/jpeg",
                bytes = captured.bytes.size,
                width = captured.width,
                height = captured.height,
            )
        } else {
            null
        }
        return toolResult(envelope, listOfNotNull(image))
    }

    private fun pageInfo(): BrowserToolResult {
        val view = requirePage()
        val value = evaluateObject(view, BrowserDomScripts.pageInfo())
        return toolResult(mergeValue(baseEnvelope("get_page_info", true, "ok"), value))
    }

    private fun historyNavigation(action: String, backwards: Boolean): BrowserToolResult {
        val view = requirePage()
        val hasTarget = callOnMain {
            val history = view.copyBackForwardList()
            val targetIndex = history.currentIndex + if (backwards) -1 else 1
            targetIndex in 0 until history.size
        }
        if (!hasTarget) throw BrowserFailure("HISTORY_UNAVAILABLE", "当前没有可用的浏览记录")
        val epoch = activeOperationEpoch
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) return@callOnMain
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            if (backwards) view.goBack() else view.goForward()
        }
        waitForPostAction()
        return toolResult(baseEnvelope(action, true, "ok"))
    }

    private fun reload(): BrowserToolResult {
        val view = requirePage()
        val epoch = activeOperationEpoch
        val generation = navigationGeneration.incrementAndGet()
        callOnMain {
            requireActiveOperation(epoch)
            if (navigationGeneration.get() != generation) return@callOnMain
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
            view.reload()
        }
        waitForPostAction()
        return toolResult(baseEnvelope("reload", true, "ok"))
    }

    private fun waitForSelector(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(args, required = true)!!
        val timeout = args.optLong("timeout_ms", 5_000L).coerceIn(500L, 30_000L)
        val deadline = System.currentTimeMillis() + timeout
        var state = JSONObject().put("found", false).put("visible", false)
        while (System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            state = evaluateObject(view, BrowserDomScripts.selectorState(selector))
            if (state.optBoolean("found")) {
                return toolResult(
                    mergeValue(baseEnvelope("wait_for_selector", true, "ok"), state)
                        .put("selector", selector.take(240))
                )
            }
            Thread.sleep(250L)
        }
        return toolResult(
            mergeValue(baseEnvelope("wait_for_selector", false, "not_found"), state)
                .put("code", "ELEMENT_NOT_FOUND")
                .put("message", "等待的网页元素未出现")
        )
    }

    private fun hover(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val target = targetFrom(args)
        val value = evaluateObject(view, BrowserDomScripts.hover(target.selector, target.x, target.y))
        waitForPostAction()
        return toolResult(mergeValue(baseEnvelope("hover", true, "ok"), value))
    }

    private fun getBackbone(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val depth = args.optInt("max_depth", 5).coerceIn(1, 8)
        val value = evaluateObject(view, BrowserDomScripts.backbone(depth))
        return toolResult(mergeValue(baseEnvelope("get_backbone", true, "ok"), value).put("max_depth", depth))
    }

    private fun executeJs(args: JSONObject): BrowserToolResult {
        val script = args.optString("script")
        if (script.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "execute_js 缺少 script")
        val view = requirePage()
        val raw = evaluateAsync(view, script)
        val envelope = baseEnvelope("execute_js", true, "ok")
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        if (parsed != null) mergeValue(envelope, parsed) else envelope.put("value", raw.take(8_000))
        return toolResult(envelope)
    }
    private fun fetchResource(args: JSONObject): BrowserToolResult {
        val url = args.optString("url").trim()
        if (url.isBlank()) throw BrowserFailure("INVALID_ARGUMENT", "fetch 缺少 url")
        val view = requirePage()
        val quoted = JSONObject.quote(url)
        val script = """
            const resp = await fetch($quoted, { credentials: 'include' });
            const buf = await resp.arrayBuffer();
            if (buf.byteLength > $FETCH_MAX_BYTES) {
              return { error: 'FETCH_TOO_LARGE', size: buf.byteLength };
            }
            const bytes = new Uint8Array(buf);
            let binary = '';
            const chunk = 0x8000;
            for (let i = 0; i < bytes.length; i += chunk) {
              binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            }
            return {
              base64: btoa(binary),
              contentType: resp.headers.get('content-type') || '',
              status: resp.status,
              url: resp.url,
              size: bytes.length
            };
        """.trimIndent()
        val raw = evaluateAsync(view, script)
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            throw BrowserFailure("SCRIPT_FAILED", "fetch 结果无法解析")
        }
        if (payload.has("error")) {
            throw BrowserFailure("FETCH_FAILED", payload.optString("error").ifBlank { "fetch 失败" })
        }
        val base64 = payload.optString("base64")
        val data = if (base64.isNotBlank()) Base64.decode(base64, Base64.DEFAULT) else ByteArray(0)
        if (data.size > FETCH_MAX_BYTES) throw BrowserFailure("FETCH_TOO_LARGE", "下载内容超过大小限制")
        val directory = File(TerminalRuntime.workspace("root"), "browser").apply { mkdirs() }
        val extension = extensionForMime(payload.optString("contentType"))
        val file = File(directory, "fetch_${System.currentTimeMillis()}.$extension")
        file.writeBytes(data)
        val envelope = baseEnvelope("fetch", true, "ok")
            .put("requested_url", url)
            .put("final_url", payload.optString("url"))
            .put("http_status", payload.optInt("status"))
            .put("mime", payload.optString("contentType"))
            .put("bytes", data.size)
            .put("path", file.absolutePath)
            .put("workspace_path", "/workspace/browser/${file.name}")
            .put("minis_path", "/var/minis/browser/${file.name}")
        return toolResult(envelope)
    }
    private fun getCookies(args: JSONObject): BrowserToolResult {
        if (currentUrl.isBlank()) throw BrowserFailure("NO_PAGE", "当前没有网页，请先调用 navigate")
        val raw = CookieManager.getInstance().getCookie(currentUrl).orEmpty()
        val keywords = BrowserCookieOffload.keywords(args)
        val fuzzy = args.optBoolean("fuzzy", true)
        val cookies = raw.split(';').mapNotNull { part ->
            val item = part.trim()
            if (item.isEmpty() || '=' !in item) return@mapNotNull null
            val name = item.substringBefore('=').trim()
            val value = item.substringAfter('=').trim()
            if (name.isEmpty()) null else name to value
        }.filter { (name, _) -> BrowserCookieOffload.matches(name, keywords, fuzzy) }
            .take(80)
        val context = appContext
            ?: throw BrowserFailure("BROWSER_NOT_INITIALIZED", "浏览器尚未初始化")
        val offloadDir = File(LinuxGuestPathResolver.workspaceHostForApp(context), "offloads")
        val file = runCatching {
            BrowserCookieOffload.write(
                directory = offloadDir,
                host = hostOf(currentUrl).ifBlank { "site" },
                url = currentUrl,
                cookies = cookies,
            )
        }.getOrElse {
            throw BrowserFailure("COOKIE_OFFLOAD_FAILED", "无法写入 cookie env 文件")
        }
        return toolResult(
            baseEnvelope("get_cookies", true, "ok")
                .put("cookie_count", cookies.size)
                .put("cookie_names", BrowserCookieOffload.namesArray(cookies))
                .put("env_path", "/workspace/offloads/${file.name}")
                .put("minis_env_path", "/var/minis/offloads/${file.name}")
                .put(
                    "note",
                    "明文 cookie 已写入 env 文件，未包含在此结果中。" +
                        "Linux 中执行: . /var/minis/offloads/${file.name}" +
                        " 或 . /workspace/offloads/${file.name}。" +
                        "变量名为 COOKIE_<NAME>，例如 COOKIE_SESSDATA。",
                ),
        )
    }

    private fun setCookies(args: JSONObject): BrowserToolResult {
        if (currentUrl.isBlank()) throw BrowserFailure("NO_PAGE", "当前没有网页，请先调用 navigate")
        val cookies = args.optJSONArray("cookies")
            ?: args.optString("cookies").takeIf { it.isNotBlank() }?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: throw BrowserFailure("INVALID_ARGUMENT", "set_cookies 缺少 cookies")
        if (cookies.length() == 0) throw BrowserFailure("INVALID_ARGUMENT", "set_cookies 缺少 cookies")
        val manager = CookieManager.getInstance()
        var written = 0
        for (index in 0 until cookies.length()) {
            val item = cookies.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val value = item.optString("value")
            if (name.isEmpty()) continue
            val domain = item.optString("domain").ifBlank { hostOf(currentUrl) }
            val pathValue = item.optString("path").ifBlank { "/" }
            val parts = mutableListOf("$name=$value", "Domain=$domain", "Path=$pathValue")
            if (item.optBoolean("secure", currentUrl.startsWith("https://"))) parts += "Secure"
            if (item.optBoolean("http_only", false) || item.optBoolean("httpOnly", false)) parts += "HttpOnly"
            if (item.has("expires")) parts += "Expires=" + item.opt("expires")?.toString()
            manager.setCookie(currentUrl, parts.joinToString("; "))
            written++
        }
        manager.flush()
        return toolResult(baseEnvelope("set_cookies", true, "ok").put("written", written))
    }
    private fun setUserAgent(args: JSONObject): BrowserToolResult {
        val profile = BrowserUserAgent.fromWire(args.optString("user_agent"))
            ?: throw BrowserFailure("INVALID_ARGUMENT", "user_agent 仅支持 desktop_chrome 或 mobile_chrome")
        val view = ensureWebView()
        val reloadNeeded = currentUrl.isNotBlank()
        callOnMain {
            userAgentProfile = profile
            sessionViewport = null
            BrowserUserAgent.persist(profile)
            applyBrowserSettingsOnMain(view)
            if (attachedContainer == null || view.parent !== attachedContainer) {
                layoutOffscreenOnMain(view, profile.viewportWidth, profile.viewportHeight)
            }
            publishSnapshotOnMain()
            if (reloadNeeded) view.reload()
        }
        if (reloadNeeded) waitForPostAction()
        return toolResult(
            baseEnvelope("set_user_agent", true, "ok")
                .put("user_agent", profile.wireName)
                .put("desktop_mode", profile.desktop)
                .put("viewport_width", profile.viewportWidth)
                .put("viewport_height", profile.viewportHeight)
        )
    }

    private fun setViewport(args: JSONObject): BrowserToolResult {
        if (args.optBoolean("reset", false)) {
            sessionViewport = null
        } else {
            val width = args.optInt("viewport_width", 0)
            val height = args.optInt("viewport_height", 0)
            if (width < 320 || height < 320) {
                throw BrowserFailure("INVALID_ARGUMENT", "set_viewport 需要 viewport_width 与 viewport_height，且不小于 320")
            }
            sessionViewport = width.coerceAtMost(2560) to height.coerceAtMost(FULL_PAGE_MAX_HEIGHT)
        }
        val view = ensureWebView()
        val (width, height) = currentViewportPx()
        val shouldReload = currentUrl.isNotBlank()
        callOnMain {
            if (attachedContainer == null || view.parent !== attachedContainer) {
                layoutOffscreenOnMain(view, width, height)
            }
            publishSnapshotOnMain()
            if (shouldReload) view.reload()
        }
        if (shouldReload) waitForPostAction()
        return toolResult(
            baseEnvelope("set_viewport", true, "ok")
                .put("viewport_width", width)
                .put("viewport_height", height)
                .put("reset", sessionViewport == null)
        )
    }
    private fun scrollAndCollect(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val selector = validatedSelector(
            JSONObject().put("selector", args.optString("item_selector").ifBlank { args.optString("selector") }),
            required = true,
        )!!
        val iterations = args.optInt("scroll_count", 5).coerceIn(1, 30)
        val keywords = jsonStringList(args, "keywords")
        val seen = LinkedHashSet<String>()
        val collected = JSONArray()
        repeat(iterations) { step ->
            throwIfInterrupted()
            val batch = evaluateObject(view, BrowserDomScripts.collectItems(selector))
            val items = batch.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val text = item.optString("text")
                    if (text.isBlank() || text in seen) continue
                    if (keywords.isNotEmpty() && keywords.none { text.contains(it, ignoreCase = true) }) continue
                    seen += text
                    collected.put(item)
                    if (collected.length() >= 80) break
                }
            }
            if (step < iterations - 1 && collected.length() < 80) {
                evaluateObject(view, BrowserDomScripts.scrollByViewport())
                Thread.sleep(350)
            }
        }
        return toolResult(
            baseEnvelope("scroll_and_collect", true, "ok")
                .put("scroll_count", iterations)
                .put("item_selector", selector)
                .put("matched", collected.length())
                .put("items", collected)
                .put("elements", collected)
        )
    }

    private fun waitForDomStable(args: JSONObject): BrowserToolResult {
        val view = requirePage()
        val timeout = when {
            args.has("timeout_ms") -> args.optLong("timeout_ms")
            args.has("timeout") -> args.optLong("timeout")
            else -> 5_000L
        }.coerceIn(1_000L, 30_000L)
        val deadline = System.currentTimeMillis() + timeout
        var last = -1
        var stable = false
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            val signature = evaluateObject(view, BrowserDomScripts.bodySignature())
            val size = signature.optInt("body_length", -1)
            if (size == last && size > 0) {
                stable = true
                break
            }
            last = size
            Thread.sleep(200)
        }
        val elapsed = System.currentTimeMillis() - started
        return toolResult(
            baseEnvelope("wait_for_dom_stable", stable, if (stable) "ok" else "timeout")
                .put("elapsed_ms", elapsed)
                .put("body_length", last)
                .put("stable", stable)
        )
    }
    private fun attachVisualSnapshot(
        action: String,
        result: BrowserToolResult,
        userInitiated: Boolean,
    ): BrowserToolResult {
        if (userInitiated || action !in VISUAL_ACTIONS || result.images.isNotEmpty()) return result
        val ok = runCatching { JSONObject(result.content).optBoolean("ok", false) }.getOrDefault(false)
        if (!ok) return result
        val preview = runCatching { capturePreview() }.getOrNull() ?: return result
        val envelope = runCatching { JSONObject(result.content) }.getOrNull() ?: return result
        envelope.put("snapshot_attached", true)
        envelope.put("snapshot_width", preview.width)
        envelope.put("snapshot_height", preview.height)
        return result.copy(content = BrowserPayloadLimiter.serialize(envelope), images = result.images + preview)
    }

    private fun captureFullPage(view: WebView): CapturedImage {
        if (attachedContainer != null && view.parent === attachedContainer) {
            return captureViewport(view)
        }
        val info = evaluateObject(view, BrowserDomScripts.pageInfo())
        val contentHeight = info.optInt("content_height", 0).coerceAtLeast(view.height)
        val targetHeight = contentHeight.coerceAtMost(FULL_PAGE_MAX_HEIGHT)
        val targetWidth = view.width.coerceAtLeast(currentViewportPx().first)
        callOnMain { layoutOffscreenOnMain(view, targetWidth, targetHeight) }
        Thread.sleep(120)
        val captured = captureViewport(
            view,
            maxWidth = SCREENSHOT_MAX_WIDTH,
            maxHeight = FULL_PAGE_MAX_HEIGHT,
            quality = SCREENSHOT_QUALITY,
        )
        callOnMain { layoutOffscreenOnMain(view, currentViewportPx().first, currentViewportPx().second) }
        return captured
    }

    private fun evaluateAsync(view: WebView, script: String): String {
        throwIfInterrupted()
        val epoch = activeOperationEpoch
        val future = CompletableFuture<String>()
        pendingAsyncJs = future
        val wrapped = """
            (async function(){
              try {
                var __r__ = await (async function(){ $script })();
                if (__r__ === undefined || __r__ === null) { __eta__.resolve(''); }
                else if (typeof __r__ === 'object') { __eta__.resolve(JSON.stringify(__r__)); }
                else { __eta__.resolve(String(__r__)); }
              } catch (error) {
                __eta__.reject(error && error.message ? error.message : String(error));
              }
            })();
        """.trimIndent()
        mainHandler.post {
            if (webView !== view || interrupted.get() || activeOperationEpoch != epoch || epoch == 0L) {
                future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
            } else {
                runCatching { view.evaluateJavascript(wrapped, null) }.onFailure(future::completeExceptionally)
            }
        }
        return try {
            future.get(ASYNC_JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw BrowserFailure("SCRIPT_TIMEOUT", "网页脚本超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            if (pendingAsyncJs === future) pendingAsyncJs = null
            throwIfInterrupted()
        }
    }

    private fun jsonStringList(args: JSONObject, key: String): List<String> {
        val array = args.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun extensionForMime(mime: String): String {
        val lowered = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return when {
            "json" in lowered -> "json"
            "html" in lowered -> "html"
            "javascript" in lowered -> "js"
            "png" in lowered -> "png"
            "jpeg" in lowered || "jpg" in lowered -> "jpg"
            "webp" in lowered -> "webp"
            "gif" in lowered -> "gif"
            "pdf" in lowered -> "pdf"
            "svg" in lowered -> "svg"
            "xml" in lowered -> "xml"
            "text/plain" == lowered -> "txt"
            else -> "bin"
        }
    }

    private fun targetFrom(args: JSONObject): BrowserTarget {
        val selector = validatedSelector(args, required = false)
        val hasX = args.has("coordinate_x") && !args.isNull("coordinate_x")
        val hasY = args.has("coordinate_y") && !args.isNull("coordinate_y")
        if (hasX != hasY) {
            throw BrowserFailure("INVALID_ARGUMENT", "coordinate_x 与 coordinate_y 必须同时提供")
        }
        if (selector == null && !hasX) {
            throw BrowserFailure("INVALID_ARGUMENT", "需要 selector 或 coordinate_x/coordinate_y")
        }
        val x = if (hasX) args.optInt("coordinate_x") else null
        val y = if (hasY) args.optInt("coordinate_y") else null
        return BrowserTarget(
            selector = selector,
            x = x,
            y = y,
        )
    }

    private fun validatedSelector(args: JSONObject, required: Boolean): String? {
        val selector = args.optString("selector").trim()
        if (selector.isBlank()) {
            if (required) throw BrowserFailure("INVALID_ARGUMENT", "缺少 CSS selector")
            return null
        }
        return selector
    }

    private fun requirePage(): WebView {
        val view = ensureWebView()
        if (!snapshots.value.available || currentUrl.isBlank()) {
            throw BrowserFailure("NO_PAGE", "当前没有网页，请先调用 navigate")
        }
        throwIfInterrupted()
        return view
    }

    private fun waitForPostAction() {
        Thread.sleep(250L)
        val deadline = System.currentTimeMillis() + POST_ACTION_TIMEOUT_MS
        while (snapshots.value.isLoading && System.currentTimeMillis() < deadline) {
            throwIfInterrupted()
            Thread.sleep(100L)
        }
        if (snapshots.value.isLoading) {
            navigationGeneration.incrementAndGet()
            mainHandler.post { runCatching { webView?.stopLoading() } }
            throw BrowserFailure("ACTION_TIMEOUT", "网页操作后的页面加载超时", "timeout")
        }
    }

    private fun throwIfInterrupted() {
        if (interrupted.get() || activeOperationEpoch == 0L) {
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        }
    }

    private fun requireActiveOperation(epoch: Long) {
        if (epoch == 0L || activeOperationEpoch != epoch || interrupted.get()) {
            throw BrowserFailure("CANCELLED", "操作已取消", "cancelled")
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun ensureWebView(): WebView {
        webView?.let { view ->
            callOnMain { applyBrowserSettingsOnMain(view) }
            return view
        }
        return callOnMain {
            webView ?: run {
                val base = appContext ?: error("browser context unavailable")
                val wrapper = MutableContextWrapper(attachedContainer?.context ?: base)
                val view = WebView(wrapper).apply {
                    setBackgroundColor(Color.WHITE)
                    addJavascriptInterface(pageBridge, "__eta__")
                    webViewClient = BrowserClient()
                    webChromeClient = BrowserChrome()
                }
                applyBrowserSettingsOnMain(view)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                contextWrapper = wrapper
                webView = view
                layoutOffscreenOnMain(view)
                attachedContainer?.let { attachWebViewOnMain(view, it) }
                publishSnapshotOnMain()
                view
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun applyBrowserSettingsOnMain(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = true
        view.settings.allowContentAccess = true
        view.settings.allowFileAccessFromFileURLs = true
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        view.settings.safeBrowsingEnabled = false
        view.settings.setSupportZoom(true)
        view.settings.builtInZoomControls = true
        view.settings.displayZoomControls = false
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.settings.userAgentString = userAgentProfile.userAgent
    }

    private fun attachWebViewOnMain(view: WebView, container: ViewGroup) {
        (view.parent as? ViewGroup)?.takeIf { it !== container }?.removeView(view)
        if (view.parent == null) {
            container.removeAllViews()
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
        }
    }

    private fun layoutOffscreenOnMain(view: WebView, widthOverride: Int? = null, heightOverride: Int? = null) {
        if (attachedContainer != null && view.parent === attachedContainer && widthOverride == null) return
        if (widthOverride == null && view.width > 0 && view.height > 0 && sessionViewport == null) return
        val (defaultWidth, defaultHeight) = currentViewportPx()
        val width = (widthOverride ?: defaultWidth).coerceAtLeast(320)
        val height = (heightOverride ?: defaultHeight).coerceAtLeast(320)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun currentViewportPx(): Pair<Int, Int> {
        sessionViewport?.let { return it }
        if (userAgentProfile.desktop) {
            return userAgentProfile.viewportWidth to userAgentProfile.viewportHeight
        }
        val metrics = (appContext ?: return userAgentProfile.viewportWidth to userAgentProfile.viewportHeight)
            .resources.displayMetrics
        return metrics.widthPixels.coerceIn(720, SCREENSHOT_MAX_WIDTH) to
            metrics.heightPixels.coerceIn(1_280, SCREENSHOT_MAX_HEIGHT)
    }

    private fun destroyWebViewOnMain() {
        val view = webView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.stopLoading() }
        runCatching { view.clearHistory() }
        runCatching { view.clearCache(true) }
        runCatching { view.clearFormData() }
        runCatching { view.destroy() }
        webView = null
        contextWrapper = null
    }

    private fun clearSessionStateOnMain() {
        currentUrl = ""
        currentHost = ""
        currentTitle = ""
        currentError = null
        currentHttpStatus = null
        currentProgress = 0
        currentLoading = false
        currentPageVisible = false
        committedMainFrameUrl = ""
        lastAgentRunId = null
        lastAgentToolCallId = null
        navigationGeneration.incrementAndGet()
        publishSnapshotOnMain()
    }

    private fun evaluateObject(view: WebView, body: String): JSONObject {
        throwIfInterrupted()
        val epoch = activeOperationEpoch
        val future = CompletableFuture<String>()
        mainHandler.post {
            if (webView !== view || interrupted.get() || activeOperationEpoch != epoch || epoch == 0L) {
                future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
            } else {
                runCatching {
                    view.evaluateJavascript(BrowserDomScripts.wrap(body)) { raw ->
                        if (!interrupted.get() && activeOperationEpoch == epoch) {
                            future.complete(raw ?: "null")
                        } else {
                            future.completeExceptionally(BrowserFailure("CANCELLED", "操作已取消", "cancelled"))
                        }
                    }
                }.onFailure(future::completeExceptionally)
            }
        }
        val raw = try {
            future.get(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            if (activeOperationEpoch == epoch) {
                interrupted.set(true)
                operationEpoch.incrementAndGet()
                activeOperationEpoch = 0L
            }
            mainHandler.post { runCatching { view.stopLoading() } }
            throw BrowserFailure("SCRIPT_TIMEOUT", "网页响应超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        throwIfInterrupted()
        return decodeEvaluation(raw)
    }

    private fun decodeEvaluation(raw: String): JSONObject {
        val outer = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        val decoded = when (outer) {
            is String -> outer
            null, JSONObject.NULL -> throw BrowserFailure("SCRIPT_FAILED", "网页没有返回可读结果")
            else -> outer.toString()
        }
        val envelope = runCatching { JSONObject(decoded) }
            .getOrElse { throw BrowserFailure("SCRIPT_FAILED", "网页结果格式无效") }
        if (!envelope.optBoolean("ok", false)) {
            val code = envelope.optString("error")
            val message = when {
                code.contains("TARGET_NOT_FOUND") ||
                    code.contains("TARGET_NOT_VISIBLE") ||
                    code.contains("TARGET_OCCLUDED") -> "目标网页元素不可见或被其他内容遮挡"
                code.contains("TARGET_DISABLED") -> "目标网页元素当前不可操作"
                code.contains("TARGET_NOT_EDITABLE") -> "目标网页元素不可输入"
                code.contains("not a valid selector", ignoreCase = true) -> "CSS selector 无效"
                else -> "网页元素操作失败"
            }
            throw BrowserFailure("SCRIPT_FAILED", message)
        }
        val value = envelope.opt("value")
        if (value == null || value === JSONObject.NULL) return JSONObject()
        if (value is JSONObject) return value
        if (value is JSONArray) return JSONObject().put("items", value)
        return JSONObject().put("value", value)
    }

    private fun captureViewport(
        view: WebView,
        maxWidth: Int = SCREENSHOT_MAX_WIDTH,
        maxHeight: Int = SCREENSHOT_MAX_HEIGHT,
        quality: Int = SCREENSHOT_QUALITY,
    ): CapturedImage = callOnMain {
        layoutOffscreenOnMain(view)
        val sourceWidth = view.width.coerceAtLeast(1)
        val sourceHeight = view.height.coerceAtLeast(1)
        val scale = minOf(
            1f,
            maxWidth.toFloat() / sourceWidth,
            maxHeight.toFloat() / sourceHeight,
        )
        val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        if (view.windowToken == null) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val bitmap = createBitmap(width, height)
        Canvas(bitmap).also { canvas ->
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            view.draw(canvas)
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
        val captured = CapturedImage(bytes, bitmap.width, bitmap.height)
        bitmap.recycle()
        captured
    }

    private fun baseEnvelope(action: String, ok: Boolean, status: String): JSONObject {
        val snapshot = snapshots.value
        return JSONObject()
            .put("ok", ok)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("status", status)
            .put("url", currentUrl)
            .put("display_url", currentUrl)
            .put("host", snapshot.host)
            .put("title", snapshot.title)
            .put("is_loading", snapshot.isLoading)
            .put("can_go_back", snapshot.canGoBack)
            .put("can_go_forward", snapshot.canGoForward)
            .also { json ->
                currentHttpStatus?.let { json.put("http_status", it) }
            }
    }

    private fun mergeValue(target: JSONObject, value: JSONObject): JSONObject = target.apply {
        value.keys().forEach { key -> put(key, value.opt(key)) }
    }

    private fun toolResult(
        envelope: JSONObject,
        images: List<BrowserImage> = emptyList(),
    ): BrowserToolResult = BrowserToolResult(
        content = BrowserPayloadLimiter.serialize(envelope),
        images = images,
    )

    private fun failureResult(action: String, throwable: Throwable): BrowserToolResult {
        val failure = throwable as? BrowserFailure
        val message = failure?.message ?: "浏览器操作失败"
        if (failure?.code !in setOf("CANCELLED", "USER_CONTROL_ACTIVE")) {
            runCatching {
                callOnMain {
                    currentLoading = false
                    currentPageVisible = committedMainFrameUrl.isNotBlank()
                    currentError = message
                    publishSnapshotOnMain()
                }
            }
        }
        return errorResult(
            action = action,
            code = failure?.code ?: "BROWSER_ERROR",
            message = message,
            status = failure?.status ?: "error",
        )
    }

    private fun errorResult(
        action: String,
        code: String,
        message: String,
        status: String = "error",
    ): BrowserToolResult = toolResult(
        baseEnvelope(action, ok = false, status = status)
            .put("code", code)
            .put("message", message)
    )

    private fun publishSnapshotOnMain() {
        val view = webView
        val pageAvailable = view != null && currentUrl.isNotBlank()
        mutableSnapshots.value = BrowserSessionSnapshot(
            available = pageAvailable,
            url = if (pageAvailable) currentUrl else "",
            displayUrl = if (pageAvailable) currentUrl else "",
            host = if (pageAvailable) currentHost else "",
            title = safeTitle(currentTitle),
            isLoading = currentLoading,
            isPageVisible = currentPageVisible,
            hasCommittedPage = committedMainFrameUrl.isNotBlank(),
            progress = currentProgress.coerceIn(0, 100),
            canGoBack = runCatching { view?.canGoBack() == true }.getOrDefault(false),
            canGoForward = runCatching { view?.canGoForward() == true }.getOrDefault(false),
            error = currentError,
            isUserControlling = userControlActive,
            lastAgentRunId = lastAgentRunId,
            lastAgentToolCallId = lastAgentToolCallId,
            desktopMode = userAgentProfile.desktop,
            userAgent = userAgentProfile.wireName,
        )
    }

    private fun safeTitle(value: String): String =
        value.filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")

    private fun setNavigationErrorOnMain(code: String, message: String) {
        navigationGeneration.incrementAndGet()
        currentLoading = false
        currentPageVisible = committedMainFrameUrl.isNotBlank()
        currentError = message
        currentLoadWaiter?.complete(LoadOutcome(false, code, message))
        publishSnapshotOnMain()
    }

    private fun <T> callOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val future = CompletableFuture<T>()
        mainHandler.post {
            runCatching(block)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally)
        }
        return try {
            future.get(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw BrowserFailure("MAIN_THREAD_TIMEOUT", "浏览器主线程响应超时", "timeout")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private class BrowserClient : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            currentTitle = view.title.orEmpty()
            currentError = null
            currentHttpStatus = null
            currentLoading = true
            currentPageVisible = false
            currentProgress = 0
            publishSnapshotOnMain()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            committedMainFrameUrl = currentUrl
            currentPageVisible = true
            currentTitle = view.title.orEmpty()
            currentLoading = false
            currentProgress = 100
            publishSnapshotOnMain()
            currentLoadWaiter?.complete(LoadOutcome(true, "OK", ""))
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            committedMainFrameUrl = url.orEmpty()
            currentPageVisible = true
            publishSnapshotOnMain()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            currentUrl = url.orEmpty()
            currentHost = hostOf(currentUrl)
            currentTitle = view.title.orEmpty()
            publishSnapshotOnMain()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            setNavigationErrorOnMain("NETWORK_ERROR", "页面加载失败，请检查网络连接")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (!request.isForMainFrame) return
            currentHttpStatus = errorResponse.statusCode
            if (errorResponse.statusCode >= 400) {
                currentError = "网页返回 HTTP ${errorResponse.statusCode}"
            }
            publishSnapshotOnMain()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            mainHandler.post {
                if (webView === view) {
                    destroyWebViewOnMain()
                    currentError = "网页渲染进程已退出，请重新打开页面"
                    currentLoading = false
                    currentPageVisible = false
                    committedMainFrameUrl = ""
                    publishSnapshotOnMain()
                }
            }
            currentLoadWaiter?.complete(LoadOutcome(false, "RENDERER_GONE", "网页渲染进程已退出"))
            return true
        }
    }

    private class BrowserChrome : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            currentTitle = title.orEmpty()
            publishSnapshotOnMain()
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            currentProgress = newProgress.coerceIn(0, 100)
            currentLoading = newProgress < 100
            publishSnapshotOnMain()
        }
    }

    private data class BrowserTarget(
        val selector: String?,
        val x: Int?,
        val y: Int?,
    )

    private data class CapturedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private data class LoadOutcome(
        val ok: Boolean,
        val code: String,
        val message: String,
    )

    private class LoadWaiter {
        private val latch = CountDownLatch(1)

        @Volatile
        private var outcome: LoadOutcome? = null

        fun complete(value: LoadOutcome) {
            if (outcome != null) return
            synchronized(this) {
                if (outcome == null) {
                    outcome = value
                    latch.countDown()
                }
            }
        }

        fun await(timeoutMs: Long): LoadOutcome? =
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) outcome else null
    }

    private class PageBridge {
        @JavascriptInterface
        fun resolve(value: String?) {
            pendingAsyncJs?.complete(value.orEmpty())
        }

        @JavascriptInterface
        fun reject(error: String?) {
            pendingAsyncJs?.completeExceptionally(
                BrowserFailure("SCRIPT_FAILED", error?.ifBlank { "JavaScript 执行失败" } ?: "JavaScript 执行失败"),
            )
        }
    }

    private class BrowserFailure(
        val code: String,
        override val message: String,
        val status: String = "error",
    ) : RuntimeException(message)

    private val SUPPORTED_ACTIONS = setOf(
        "navigate",
        "get_readable",
        "get_text",
        "find_elements",
        "click",
        "type",
        "scroll",
        "screenshot",
        "get_page_info",
        "go_back",
        "go_forward",
        "reload",
        "wait_for_selector",
        "execute_js",
        "get_backbone",
        "hover",
        "fetch",
        "get_cookies",
        "set_cookies",
        "set_user_agent",
        "set_viewport",
        "scroll_and_collect",
        "wait_for_dom_stable",
    )

    private val VISUAL_ACTIONS = setOf("navigate", "click", "type", "scroll", "hover")

}
