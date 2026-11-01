package love.nairain.xiaoai.inline

import android.inputmethodservice.InputMethodService
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ImeCompatibilityTest {
    @Test fun oldVersionsReturnKeyboardContainerDirectly() {
        val keyboard = Keyboard(Node())
        assertSame(keyboard, find(keyboard))
    }

    @Test fun newVersionWrapsKeyboardContainer() {
        val keyboard = Keyboard(Node())
        assertSame(keyboard, find(Node(keyboard)))
    }

    @Test fun extraWrapperAndSiblingDoNotChangeSelection() {
        val keyboard = Keyboard(Node())
        assertSame(keyboard, find(Node(Node(), Node(Node(), keyboard))))
    }

    @Test fun unknownViewDoesNotSelectAnArbitraryGroup() {
        assertNull(find(Node(Node(), Node(Node()))))
        assertNull(find(null))
    }

    @Test fun multipleKeyboardContainersAreRejected() {
        assertNull(find(Node(Keyboard(), Keyboard())))
        assertNull(find(Keyboard(Keyboard())))
    }

    @Test fun targetsSurviveRepeatedHotReloadWithoutDexKit() {
        var targets = ImeTargets.validated(Service::class.java, Container::class.java)
        repeat(3) {
            val state = targets.save()
            assertEquals(setOf("schema", "service", "container"), state.keys)
            targets = ImeTargets.restore(state, javaClass.classLoader!!)
            assertSame(Service::class.java, targets.serviceClass)
            assertSame(Container::class.java, targets.containerClass)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameworkServiceIsNotAHostService() {
        ImeTargets.validated(InputMethodService::class.java, Container::class.java)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ordinaryFrameLayoutIsNotAKeyboardContainer() {
        ImeTargets.validated(Service::class.java, FrameLayout::class.java)
    }

    @Test(expected = IllegalArgumentException::class)
    fun containerMustBelongToSelectedService() {
        ImeTargets.validated(OtherService::class.java, Container::class.java)
    }

    @Test(expected = IllegalArgumentException::class)
    fun changedResolutionSchemaRequiresProcessRestart() {
        val state = ImeTargets.validated(Service::class.java, Container::class.java).save().toMutableMap()
        state["schema"] = -1
        ImeTargets.restore(state, javaClass.classLoader!!)
    }

    @Test(expected = IllegalArgumentException::class)
    fun differentHostClassIdentityCannotReuseTargets() {
        val state = ImeTargets.validated(Service::class.java, Container::class.java).save()
        val serviceName = Service::class.java.name
        val serviceBytes = Service::class.java.getResourceAsStream(
            "/${serviceName.replace('.', '/')}.class"
        )!!.use { it.readBytes() }
        val wrongLoader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name == serviceName) findLoadedClass(name) ?: defineClass(name, serviceBytes, 0, serviceBytes.size)
                else super.loadClass(name, resolve)
        }
        ImeTargets.restore(state, wrongLoader)
    }

    private fun find(root: Node?): Node? = findUniqueContainer(root, Keyboard::class.java) { it.children }

    private open class Node(vararg children: Node) {
        val children = children.toList()
    }
    private class Keyboard(vararg children: Node) : Node(*children)
    private class Service : InputMethodService()
    private class OtherService : InputMethodService()
    private class Container(service: Service) : FrameLayout(service)
}
