package love.nairain.xiaoai.inline

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionInfo
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import love.nairain.xiaoai.ReloadableHooks
import java.lang.reflect.Method
import java.util.IdentityHashMap

internal class XiaoAiImeHook(
    private val module: XposedModule,
    private val hooks: ReloadableHooks,
    private val targets: ImeTargets,
    private val enabled: () -> Boolean,
    private val report: (String) -> Unit,
) {
    private val serviceClass = targets.serviceClass
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeService: InputMethodService? = null
    private var host: InlineSuggestionHost? = null
    private val session = SuggestionSession()
    private val delivery = SuggestionDelivery<InlineSuggestion>()
    private val authenticationRefresh = AuthenticationRefresh()
    private var lastTop: Int? = null
    private var supportedView: Boolean? = null
    private var inputContainer: FrameLayout? = null
    private val reloadGate = ReloadGate()
    // 系统的 Binder 包装仅弱引用 inflate 回调；首帧返回前须保活 InlineSuggestion。
    private val inflatingSuggestions = IdentityHashMap<InlineSuggestion, Int>()

    fun install() {
        intercept("onCreateInlineSuggestionsRequest", Bundle::class.java) { service, _ ->
            bind(service)
            if (!enabled() || supportedView == false) null else {
                session.reset()
                delivery.startInput()
                clear("request")
                val width = (host?.parent as? View)?.width?.takeIf { it > 0 }
                    ?: service.resources.displayMetrics.widthPixels
                report("REQUEST width=$width")
                InlineRequestFactory.create(service, width)
            }
        }
        intercept("onInlineSuggestionsResponse", InlineSuggestionsResponse::class.java) { service, args ->
            if (!enabled()) false else {
                bind(service)
                showResponse(service, args[0] as InlineSuggestionsResponse)
            }
        }
        after("onCreateInputView") { service, _, result ->
            bind(service)
            // View 重建后不能让旧容器上的异步 inflate 回调重新挂载内容。
            if (host != null) clear("view-recreated", preservePending = true)
            val root = result as? View
            // 不同版本直接返回键盘容器或增加外层包装，按 DEX 定位的类型查找唯一容器。
            val container = targets.findContainer(root)
            inputContainer = container
            supportedView = container != null
            if (container != null) {
                attachHost(service, container)
                report("HOST_ATTACHED root=${root?.javaClass?.name} container=${container.javaClass.name}")
                showPendingSuggestions(service)
            } else {
                clear("unsupported-view")
                (host?.parent as? ViewGroup)?.removeView(host)
                host = null
                report("HOST_UNAVAILABLE root=${root?.javaClass?.name}")
            }
        }
        before("onStartInput", EditorInfo::class.java, Boolean::class.javaPrimitiveType!!) { service, args ->
            bind(service)
            authenticationRefresh.startInput(editorKey(args[0] as? EditorInfo))
            if (args[1] != true) session.reset()
            delivery.startInput()
            clear("start-input")
        }
        after("onStartInputView", EditorInfo::class.java, Boolean::class.javaPrimitiveType!!) { service, _, _ ->
            bind(service)
            delivery.startView()
            host?.setInputVisible(true)
            showPendingSuggestions(service)
            refreshAfterAuthentication(service)
            report("INPUT_VIEW_STARTED")
        }
        after("onComputeInsets", InputMethodService.Insets::class.java) { _, args, _ ->
            val insets = args[0] as InputMethodService.Insets
            lastTop = insets.visibleTopInsets
            host?.positionAt(insets.visibleTopInsets)
        }
        after("onFinishInputView", Boolean::class.javaPrimitiveType!!) { _, args, _ ->
            hideInputView("finish-view", finishingInput = args[0] == true)
        }
        after("onFinishInput") { _, _, _ ->
            delivery.finishInput()
            host?.setInputVisible(false)
            clear("finish-input")
        }
        after("onWindowHidden") { _, _, _ ->
            hideInputView("window-hidden")
        }
        before("onConfigurationChanged", Configuration::class.java) { _, _ ->
            authenticationRefresh.cancel()
            delivery.finishInput()
            clear("configuration")
        }
        after("onDestroy") { _, _, _ ->
            authenticationRefresh.cancel()
            delivery.finishInput()
            clear("destroy")
            (host?.parent as? ViewGroup)?.removeView(host)
            host = null
            inputContainer = null
            activeService = null
        }
        deoptimizeInlineCallers()
    }

    fun onEnabledChanged(value: Boolean) {
        synchronized(mainHandler) {
            if (!value && !reloadGate.retired) mainHandler.post {
                authenticationRefresh.cancel()
                clear("disabled")
            }
        }
    }

    // 与输入法回调同在主线程，确保清理后不会重新挂入旧 View。
    fun prepareForReload(): Map<String, Any?>? {
        synchronized(mainHandler) {
            if (!reloadGate.retire()) return null
            mainHandler.removeCallbacksAndMessages(null)
        }
        val state = hashMapOf<String, Any?>(
            "service" to activeService,
            "container" to inputContainer,
            "visible" to delivery.visible,
            "top" to lastTop,
        )
        delivery.finishInput()
        authenticationRefresh.cancel()
        clear("hot-reload")
        (host?.parent as? ViewGroup)?.removeView(host)
        host = null
        inputContainer = null
        activeService = null
        return state
    }

    fun restoreAfterReload(state: Map<*, *>?) {
        val service = state?.get("service") as? InputMethodService ?: return
        bind(service)
        inputContainer = (state["container"] as? FrameLayout)?.takeIf { targets.containerClass.isInstance(it) }
        supportedView = inputContainer?.let { true }
        delivery.restoreView(state["visible"] == true)
        lastTop = state["top"] as? Int
        inputContainer?.let { attachHost(service, it) }
        // 旧请求的响应不跨代接受；切换输入框/发起新请求后恢复建议。
        report("IME_RESTORED inputVisible=${delivery.visible} attached=${host?.isAttachedToWindow == true}")
    }

    private fun attachHost(service: InputMethodService, container: FrameLayout) {
        host = (host ?: InlineSuggestionHost(service, report, ::dismissSuggestions)).also {
            it.attachTo(container)
            it.setInputVisible(delivery.visible)
        }
        lastTop?.let { host?.positionAt(it) }
    }

    private fun bind(service: InputMethodService) {
        if (activeService === service) return
        clear("service-changed")
        (host?.parent as? ViewGroup)?.removeView(host)
        host = null
        inputContainer = null
        activeService = service
        authenticationRefresh.cancel()
        session.reset()
        delivery.finishInput()
        lastTop = null
        supportedView = null
    }

    private fun clear(reason: String, preservePending: Boolean = false) {
        session.invalidate()
        if (!preservePending) delivery.clearPending()
        host?.clear()
        report("CLEAR reason=$reason")
    }

    private fun hideInputView(reason: String, finishingInput: Boolean = false) {
        authenticationRefresh.left()
        delivery.hideView(finishingInput)
        host?.setInputVisible(false)
        // 只清理已显示的旧建议，保留仍属当前输入会话、尚未 inflate 的最新响应。
        clear(reason, preservePending = delivery.active)
        report("INPUT_VIEW_HIDDEN finishing=$finishingInput active=${delivery.active}")
    }

    private fun showPendingSuggestions(service: InputMethodService) {
        if (host == null || !enabled() || session.dismissed || supportedView == false) return
        delivery.takePending()?.let {
            report("RESPONSE_RESUMED count=${it.size}")
            inflateSuggestions(service, it)
        }
    }

    private fun dismissSuggestions() {
        authenticationRefresh.cancel()
        session.dismiss()
        clear("dismissed")
    }

    private fun showResponse(service: InputMethodService, response: InlineSuggestionsResponse): Boolean {
        if (!delivery.active || session.dismissed || supportedView == false) {
            report("RESPONSE_SUPPRESSED dismissed=${session.dismissed} active=${delivery.active}")
            return false
        }
        val suggestions = response.inlineSuggestions.take(InlineRequestFactory.MAX_SUGGESTIONS)
        val credentials = suggestions.count {
            it.info.source == InlineSuggestionInfo.SOURCE_AUTOFILL &&
                it.info.type == InlineSuggestionInfo.TYPE_SUGGESTION
        }
        val actions = suggestions.count { it.info.type == InlineSuggestionInfo.TYPE_ACTION }
        if (credentials > 0) {
            authenticationRefresh.receivedCredentials(editorKey(service.currentInputEditorInfo))
        }
        report("RESPONSE count=${suggestions.size} actions=$actions suggestions=$credentials attached=${host?.isAttachedToWindow == true} inputVisible=${delivery.visible}")
        clear("response")
        if (suggestions.isEmpty()) return false
        if (host == null || !delivery.visible) {
            delivery.defer(suggestions)
            report("RESPONSE_DEFERRED count=${suggestions.size}")
            return true
        }
        inflateSuggestions(service, suggestions)
        return true
    }

    private fun inflateSuggestions(service: InputMethodService, suggestions: List<InlineSuggestion>) {
        val target = host ?: return
        val token = session.nextResponse() ?: return
        val slots = target.prepare(suggestions.size)
        val editor = editorKey(service.currentInputEditorInfo)
        suggestions.forEachIndexed { index, suggestion ->
            val isAuthentication = suggestion.info.source == InlineSuggestionInfo.SOURCE_AUTOFILL &&
                suggestion.info.type == InlineSuggestionInfo.TYPE_ACTION
            if (!reloadGate.begin()) return@forEachIndexed
            inflatingSuggestions[suggestion] = (inflatingSuggestions[suggestion] ?: 0) + 1
            var completed = false
            fun complete() {
                if (!completed) {
                    completed = true
                    val retained = checkNotNull(inflatingSuggestions[suggestion])
                    if (retained == 1) inflatingSuggestions.remove(suggestion)
                    else inflatingSuggestions[suggestion] = retained - 1
                    reloadGate.end()
                }
            }
            try {
                suggestion.inflate(
                    service,
                    Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    service.mainExecutor,
                ) { view ->
                    try {
                        if (!session.accepts(token) || !enabled() || !delivery.active || !delivery.visible ||
                            activeService !== service || host !== target) return@inflate
                        if (view != null) {
                            target.showSuggestion(slots[index], view, index) {
                                if (isAuthentication && editor != null && enabled() && !reloadGate.retired) {
                                    authenticationRefresh.clicked(editor, SystemClock.elapsedRealtime())
                                    if (!delivery.visible || editorKey(service.currentInputEditorInfo) != editor) {
                                        authenticationRefresh.left()
                                    }
                                    report("AUTH_ACTION_CLICKED")
                                }
                            }
                            lastTop?.let { target.positionAt(it) }
                        } else {
                            report("INFLATE_EMPTY index=$index")
                        }
                    } finally {
                        complete()
                    }
                }
            } catch (error: Exception) {
                complete()
                report("INFLATE_FAILED index=$index type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun editorKey(info: EditorInfo?): AutofillEditor? {
        val packageName = info?.packageName?.takeIf { it.isNotEmpty() } ?: return null
        return AutofillEditor(packageName, info.fieldId, info.inputType)
    }

    private fun refreshAfterAuthentication(service: InputMethodService) {
        if (!authenticationRefresh.needsRefresh(editorKey(service.currentInputEditorInfo),
                SystemClock.elapsedRealtime())) return
        // 下一次主线程调度时重新检查焦点；先让正常返回的密码建议有机会取消补请求。
        mainHandler.post {
            if (reloadGate.retired || !enabled() || session.dismissed || !delivery.active ||
                !delivery.visible || supportedView == false || activeService !== service) return@post
            val connection = service.currentInputConnection ?: return@post
            if (!authenticationRefresh.consume(editorKey(service.currentInputEditorInfo),
                    SystemClock.elapsedRealtime())) return@post
            try {
                // TextView 会将该标准编辑器命令转为 AutofillManager.requestAutofill。
                // 返回值只说明命令是否提交，实际更新仍须等待新的系统响应。
                val dispatched = connection.performContextMenuAction(android.R.id.autofill)
                report("AUTH_REFRESH_DISPATCHED accepted=$dispatched")
            } catch (error: Exception) {
                report("AUTH_REFRESH_FAILED type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun resolve(name: String, parameters: Array<out Class<*>>): Method {
        var current: Class<*>? = serviceClass
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, *parameters)
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodException(name)
    }

    private fun deoptimizeInlineCallers() {
        // 实机 framework.jar 已确认的调用方，防止默认空回调被 AOT 内联。
        val callers = mapOf(
            "android.inputmethodservice.InputMethodService\$\$ExternalSyntheticLambda8" to setOf("apply"),
            "android.inputmethodservice.InputMethodService\$\$ExternalSyntheticLambda10" to setOf("accept"),
            "android.inputmethodservice.InlineSuggestionSession" to setOf(
                "makeInlineSuggestionRequestUncheck", "consumeInlineSuggestionsResponse"
            ),
        )
        callers.forEach { (className, names) ->
            try {
                Class.forName(className, false, serviceClass.classLoader).declaredMethods
                    .filter { it.name in names }
                    .forEach { report("IME_DEOPT method=${it.name} success=${module.deoptimize(it)}") }
            } catch (error: Exception) {
                report("IME_DEOPT_SKIPPED type=${error.javaClass.simpleName}")
            }
        }
    }

    private fun intercept(name: String, vararg parameters: Class<*>, action: (InputMethodService, List<Any?>) -> Any?) {
        hooks.intercept(resolve(name, parameters)) { chain ->
            val service = chain.thisObject as? InputMethodService
            if (reloadGate.retired || service == null || !serviceClass.isInstance(service) || !enabled()) return@intercept chain.proceed()
            try {
                action(service, chain.args)
            } catch (error: Exception) {
                report("CALLBACK_FAILED name=$name type=${error.javaClass.simpleName}")
                chain.proceed()
            }
        }
    }

    private fun before(name: String, vararg parameters: Class<*>, action: (InputMethodService, List<Any?>) -> Unit) {
        hooks.intercept(resolve(name, parameters)) { chain ->
            val service = chain.thisObject as? InputMethodService
            if (!reloadGate.retired && service != null && serviceClass.isInstance(service)) {
                try { action(service, chain.args) } catch (error: Exception) {
                    report("LIFECYCLE_FAILED name=$name type=${error.javaClass.simpleName}")
                }
            }
            chain.proceed()
        }
    }

    private fun after(name: String, vararg parameters: Class<*>, action: (InputMethodService, List<Any?>, Any?) -> Unit) {
        hooks.intercept(resolve(name, parameters)) { chain ->
            val result = chain.proceed()
            val service = chain.thisObject as? InputMethodService
            if (!reloadGate.retired && service != null && serviceClass.isInstance(service)) {
                try { action(service, chain.args, result) } catch (error: Exception) {
                    report("LIFECYCLE_FAILED name=$name type=${error.javaClass.simpleName}")
                }
            }
            result
        }
    }
}
