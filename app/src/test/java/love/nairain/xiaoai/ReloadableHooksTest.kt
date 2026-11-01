package love.nairain.xiaoai

import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method

class ReloadableHooksTest {
    private val module = object : XposedModule() {}
    private val hooker = Hooker { it.proceed() }
    private val first = String::class.java.getDeclaredMethod("isEmpty")
    private val second = String::class.java.getDeclaredMethod("length")

    @Test fun replacesMatchingMethodAndRemovesObsoleteHook() {
        val kept = Handle(first)
        val obsolete = Handle(second)
        val hooks = ReloadableHooks(module, listOf(kept, obsolete))
        // 反射返回不同 Method 实例也必须识别为同一方法。
        hooks.intercept(String::class.java.getDeclaredMethod("isEmpty"), hooker)
        hooks.removeObsolete()
        assertNotNull(kept.replacement)
        assertEquals(0, kept.unhookCalls)
        assertEquals(0, kept.replacement!!.unhookCalls)
        assertNull(obsolete.replacement)
        assertEquals(1, obsolete.unhookCalls)
    }

    @Test fun failedReloadRemovesNewAndUnreplacedOldHooks() {
        val replaced = Handle(first)
        val remaining = Handle(second)
        val hooks = ReloadableHooks(module, listOf(replaced, remaining))
        hooks.intercept(first, hooker)
        hooks.rollback()
        assertEquals(0, replaced.unhookCalls)
        assertEquals(1, replaced.replacement!!.unhookCalls)
        assertEquals(1, remaining.unhookCalls)
    }

    @Test fun repeatedReloadAdoptsTheLatestHandle() {
        val old = Handle(first)
        ReloadableHooks(module, listOf(old)).apply {
            intercept(first, hooker)
            removeObsolete()
        }
        val current = old.replacement!!
        ReloadableHooks(module, listOf(current)).apply {
            intercept(first, hooker)
            removeObsolete()
        }
        assertNotNull(current.replacement)
        assertEquals(0, current.unhookCalls)
    }

    private class Handle(private val method: Method) : HookHandle {
        var replacement: Handle? = null
        var unhookCalls = 0
        override fun getExecutable() = method
        override fun getId(): String? = null
        override fun unhook() { unhookCalls++ }
        override fun replaceHook(hooker: Hooker): HookHandle = Handle(method).also { replacement = it }
    }
}
