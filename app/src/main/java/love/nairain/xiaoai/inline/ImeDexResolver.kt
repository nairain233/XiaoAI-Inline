package love.nairain.xiaoai.inline

import android.inputmethodservice.InputMethodService
import android.widget.FrameLayout
import org.luckypray.dexkit.DexKitBridge

internal object ImeDexResolver {
    fun resolve(classLoader: ClassLoader, report: (String) -> Unit): ImeTargets {
        System.loadLibrary("dexkit")
        // 按宿主实际 ClassLoader 加载 DEX，涵盖其中的多个 DEX 和 split APK；用完立即释放。
        return DexKitBridge.create(classLoader, false).use { bridge ->
            val inputViews = bridge.findMethod {
                matcher {
                    name = "onCreateInputView"
                    returnType = "android.view.View"
                    paramTypes()
                }
            }.filter {
                val type = Class.forName(it.className, false, classLoader)
                type != InputMethodService::class.java && InputMethodService::class.java.isAssignableFrom(type)
            }
            check(inputViews.size == 1) { "IME input view matches=${inputViews.size}" }
            val inputView = inputViews.single()
            val service = Class.forName(inputView.className, false, classLoader)
            // 容器的混淆名会变化，但其构造器接收当前输入法服务，且由输入视图回调创建。
            val containers = inputView.invokes.filter {
                it.name == "<init>" && it.paramTypeNames == listOf(service.name)
            }.map { Class.forName(it.className, false, classLoader) }
                .filter { it != FrameLayout::class.java && FrameLayout::class.java.isAssignableFrom(it) }
                .distinct()
            check(containers.size == 1) { "IME container matches=${containers.size}" }
            ImeTargets.validated(service, containers.single()).also {
                report("DEX_TARGETS_READY service=${it.serviceClass.name} container=${it.containerClass.name}")
            }
        }
    }
}
