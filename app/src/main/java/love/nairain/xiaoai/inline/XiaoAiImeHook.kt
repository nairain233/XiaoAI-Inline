package love.nairain.xiaoai.inline

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InlineSuggestion
import android.widget.FrameLayout
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal class XiaoAiImeHook(
    private val module: XposedModule,
    private val serviceClass: Class<*>,
    private val enabled: () -> Boolean,
    private val report: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeService: InputMethodService? = null
    private var host: InlineSuggestionHost? = null
    private val session = SuggestionSession()
    private var inputVisible = false
    private var acceptResponses = false
    private var lastTop: Int? = null
    private var pendingSuggestions: List<InlineSuggestion>? = null
    private var supportedView: Boolean? = null

    fun install() {
        intercept("onCreateInlineSuggestionsRequest", Bundle::class.java) { service, _ ->
            bind(service)
            if (!enabled() || supportedView == false) null else {
                session.reset()
                acceptResponses = true
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
            if (host != null) clear("view-recreated")
            val root = result as? ViewGroup
            // 实机外层容器会强制所有直接子 View 填满，建议条应加入其内部原生容器。
            val container = root?.getChildAt(0) as? FrameLayout
            supportedView = container != null
            if (container != null) {
                host = (host ?: InlineSuggestionHost(service, report, ::dismissSuggestions)).also {
                    it.attachTo(container)
                    it.setInputVisible(inputVisible)
                }
                lastTop?.let { host?.positionAt(it) }
                report("HOST_ATTACHED root=${root.javaClass.simpleName}")
                pendingSuggestions?.let { suggestions ->
                    pendingSuggestions = null
                    inflateSuggestions(service, suggestions)
                }
            } else {
                clear("unsupported-view")
                report("HOST_UNAVAILABLE")
            }
        }
        before("onStartInput", EditorInfo::class.java, Boolean::class.javaPrimitiveType!!) { service, args ->
            bind(service)
            if (args[1] != true) session.reset()
            acceptResponses = true
            clear("start-input")
        }
        after("onStartInputView", EditorInfo::class.java, Boolean::class.javaPrimitiveType!!) { service, _, _ ->
            bind(service)
            inputVisible = true
            acceptResponses = true
            host?.setInputVisible(true)
            report("INPUT_VIEW_STARTED")
        }
        after("onComputeInsets", InputMethodService.Insets::class.java) { _, args, _ ->
            val insets = args[0] as InputMethodService.Insets
            lastTop = insets.visibleTopInsets
            host?.positionAt(insets.visibleTopInsets)
        }
        after("onFinishInputView", Boolean::class.javaPrimitiveType!!) { _, _, _ ->
            inputVisible = false
            acceptResponses = false
            host?.setInputVisible(false)
            clear("finish-view")
        }
        after("onFinishInput") { _, _, _ ->
            acceptResponses = false
            clear("finish-input")
        }
        after("onWindowHidden") { _, _, _ ->
            inputVisible = false
            acceptResponses = false
            host?.setInputVisible(false)
            clear("window-hidden")
        }
        before("onConfigurationChanged", Configuration::class.java) { _, _ ->
            acceptResponses = false
            clear("configuration")
        }
        after("onDestroy") { _, _, _ ->
            acceptResponses = false
            clear("destroy")
            (host?.parent as? ViewGroup)?.removeView(host)
            host = null
            activeService = null
        }
        deoptimizeInlineCallers()
    }

    fun onEnabledChanged(value: Boolean) {
        if (!value) mainHandler.post { clear("disabled") }
    }

    private fun bind(service: InputMethodService) {
        if (activeService === service) return
        clear("service-changed")
        (host?.parent as? ViewGroup)?.removeView(host)
        host = null
        activeService = service
        session.reset()
        inputVisible = false
        acceptResponses = false
        lastTop = null
        supportedView = null
    }

    private fun clear(reason: String) {
        session.invalidate()
        pendingSuggestions = null
        host?.clear()
        report("CLEAR reason=$reason")
    }

    private fun dismissSuggestions() {
        session.dismiss()
        clear("dismissed")
    }

    private fun showResponse(service: InputMethodService, response: InlineSuggestionsResponse): Boolean {
        if (!acceptResponses || session.dismissed || supportedView == false) {
            report("RESPONSE_SUPPRESSED dismissed=${session.dismissed} active=$acceptResponses")
            return false
        }
        val suggestions = response.inlineSuggestions.take(InlineRequestFactory.MAX_SUGGESTIONS)
        report("RESPONSE count=${suggestions.size} attached=${host?.isAttachedToWindow == true} inputVisible=$inputVisible")
        clear("response")
        if (suggestions.isEmpty()) return false
        if (host == null) {
            pendingSuggestions = suggestions
            return true
        }
        inflateSuggestions(service, suggestions)
        return true
    }

    private fun inflateSuggestions(service: InputMethodService, suggestions: List<InlineSuggestion>) {
        val target = host ?: return
        val token = session.nextResponse() ?: return
        val slots = target.prepare(suggestions.size)
        suggestions.forEachIndexed { index, suggestion ->
            try {
                suggestion.inflate(
                    service,
                    Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    service.mainExecutor,
                ) { view ->
                    if (!session.accepts(token) || !enabled() || !acceptResponses ||
                        activeService !== service || host !== target) return@inflate
                    if (view != null) {
                        target.showSuggestion(slots[index], view, index)
                        lastTop?.let { target.positionAt(it) }
                    } else {
                        report("INFLATE_EMPTY index=$index")
                    }
                }
            } catch (error: Exception) {
                report("INFLATE_FAILED index=$index type=${error.javaClass.simpleName}")
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
        module.hook(resolve(name, parameters)).intercept { chain ->
            val service = chain.thisObject as? InputMethodService
            if (service == null || !serviceClass.isInstance(service) || !enabled()) return@intercept chain.proceed()
            try {
                action(service, chain.args)
            } catch (error: Exception) {
                report("CALLBACK_FAILED name=$name type=${error.javaClass.simpleName}")
                chain.proceed()
            }
        }
    }

    private fun before(name: String, vararg parameters: Class<*>, action: (InputMethodService, List<Any?>) -> Unit) {
        module.hook(resolve(name, parameters)).intercept { chain ->
            val service = chain.thisObject as? InputMethodService
            if (service != null && serviceClass.isInstance(service)) {
                try { action(service, chain.args) } catch (error: Exception) {
                    report("LIFECYCLE_FAILED name=$name type=${error.javaClass.simpleName}")
                }
            }
            chain.proceed()
        }
    }

    private fun after(name: String, vararg parameters: Class<*>, action: (InputMethodService, List<Any?>, Any?) -> Unit) {
        module.hook(resolve(name, parameters)).intercept { chain ->
            val result = chain.proceed()
            val service = chain.thisObject as? InputMethodService
            if (service != null && serviceClass.isInstance(service)) {
                try { action(service, chain.args, result) } catch (error: Exception) {
                    report("LIFECYCLE_FAILED name=$name type=${error.javaClass.simpleName}")
                }
            }
            result
        }
    }
}
