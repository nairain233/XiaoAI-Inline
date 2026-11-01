package love.nairain.xiaoai.inline

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.reflect.Modifier

// 仅保存宿主 Class，不持有 DexKit 对象或旧模块实例，供热重载跨代复用。
internal class ImeTargets private constructor(
    val serviceClass: Class<out InputMethodService>,
    val containerClass: Class<out FrameLayout>,
) {
    fun findContainer(root: View?): FrameLayout? = findUniqueContainer(root, containerClass) { view ->
        if (view is ViewGroup) List(view.childCount) { view.getChildAt(it) } else emptyList()
    } as? FrameLayout

    fun save(): Map<String, Any> = hashMapOf(
        "schema" to SCHEMA,
        "service" to serviceClass,
        "container" to containerClass,
    )

    companion object {
        private const val SCHEMA = 1

        fun validated(service: Class<*>, container: Class<*>): ImeTargets {
            require(service != InputMethodService::class.java &&
                InputMethodService::class.java.isAssignableFrom(service) &&
                !Modifier.isAbstract(service.modifiers)) { "Invalid IME service" }
            require(container != FrameLayout::class.java &&
                FrameLayout::class.java.isAssignableFrom(container) &&
                !Modifier.isAbstract(container.modifiers)) { "Invalid keyboard container" }
            require(container.declaredConstructors.any {
                it.parameterTypes.contentEquals(arrayOf(service))
            }) { "Container is not bound to IME service" }
            val createView = service.getMethod("onCreateInputView")
            require(!Modifier.isStatic(createView.modifiers) &&
                View::class.java.isAssignableFrom(createView.returnType)) { "Invalid input view callback" }
            return ImeTargets(service.asSubclass(InputMethodService::class.java),
                container.asSubclass(FrameLayout::class.java))
        }

        fun restore(state: Map<*, *>, classLoader: ClassLoader): ImeTargets {
            require(state["schema"] == SCHEMA) { "IME targets changed; restart process" }
            val service = state["service"] as? Class<*> ?: error("IME service missing")
            val container = state["container"] as? Class<*> ?: error("IME container missing")
            require(Class.forName(service.name, false, classLoader) === service &&
                Class.forName(container.name, false, classLoader) === container) { "Host classloader changed" }
            return validated(service, container)
        }
    }
}

// 兼容直接返回容器、增加外层包装和调整子 View 顺序；多处匹配时不猜测。
internal fun <T : Any> findUniqueContainer(
    root: T?,
    containerClass: Class<*>,
    children: (T) -> List<T>,
): T? {
    if (root == null) return null
    val pending = ArrayDeque<T>()
    pending.add(root)
    var found: T? = null
    while (pending.isNotEmpty()) {
        val view = pending.removeFirst()
        if (containerClass.isInstance(view)) {
            if (found != null) return null
            found = view
        }
        pending.addAll(children(view))
    }
    return found
}
