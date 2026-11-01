package love.nairain.xiaoai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.util.Log
import android.view.inputmethod.InputMethodInfo
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import love.nairain.xiaoai.inline.XiaoAiImeHook

class ModuleEntry : XposedModule() {
    private var preferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var processName = ""
    @Volatile private var enabled = false
    private var imeHook: XiaoAiImeHook? = null
    private var systemInstalled = false
    private var targetClassLoader: ClassLoader? = null
    @Volatile private var retiring = false
    private var hooks = ReloadableHooks(this)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        initialize(param)
    }

    private fun initialize(param: ModuleLoadedParam) {
        processName = param.processName
        report("MODULE_LOADED process=${param.processName} api=$apiVersion framework=$frameworkVersionCode")
        try {
            preferences = getRemotePreferences(PREFERENCES).also { prefs ->
                enabled = prefs.getBoolean(ENABLED, true)
                preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { updated, key ->
                    if (!retiring && key == ENABLED) {
                        enabled = updated.getBoolean(ENABLED, true)
                        imeHook?.onEnabledChanged(enabled)
                        report("CONFIG enabled=$enabled")
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
            }
        } catch (error: Exception) {
            report("CONFIG_UNAVAILABLE ${error.javaClass.simpleName}")
        }
    }

    // 此入口专用于系统侧 Hook，目标签名已用当前 ROM 的 services.jar 校验。
    @SuppressLint("PrivateApi")
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        targetClassLoader = param.classLoader
        installSystemHooks(param.classLoader)
    }

    @SuppressLint("PrivateApi")
    private fun installSystemHooks(classLoader: ClassLoader) {
        if (systemInstalled) return
        try {
            val controller = Class.forName(
                "com.android.server.inputmethod.AutofillSuggestionsController", false, classLoader
            )
            val gate = controller.getDeclaredMethod(
                "isInlineSuggestionsEnabled", InputMethodInfo::class.java, Boolean::class.javaPrimitiveType
            )
            hooks.intercept(gate) { chain ->
                val info = chain.args[0] as? InputMethodInfo
                val touchExploration = chain.args[1] as Boolean
                if (enabled && !touchExploration && info?.packageName == HOST_PACKAGE &&
                    info.serviceName == HOST_SERVICE
                ) {
                    report("CAPABILITY_ALLOWED")
                    true
                } else {
                    chain.proceed()
                }
            }
            // 小方法可能被编译器内联；只处理实机已确认的直接调用方。
            controller.declaredMethods.filter { it.name == "onCreateInlineSuggestionsRequest" }
                .forEach { report("SYSTEM_DEOPT success=${deoptimize(it)}") }
            systemInstalled = true
            report("SYSTEM_HOOK_READY enabled=$enabled")
        } catch (error: Exception) {
            report("SYSTEM_HOOK_FAILED ${error.javaClass.simpleName}")
            throw error
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != HOST_PACKAGE || processName != HOST_PACKAGE || imeHook != null) return
        targetClassLoader = param.classLoader
        installImeHooks(param.classLoader)
    }

    private fun installImeHooks(classLoader: ClassLoader) {
        try {
            val serviceClass = Class.forName(HOST_SERVICE, false, classLoader)
            imeHook = XiaoAiImeHook(this, hooks, serviceClass, { enabled && !retiring }, ::report)
                .also { it.install() }
            report("IME_HOOK_READY enabled=$enabled")
        } catch (error: Exception) {
            report("IME_HOOK_FAILED ${error.javaClass.simpleName}")
            throw error
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        return MainThread.run {
            val imeState = imeHook?.prepareForReload()
            if (imeHook != null && imeState == null) {
                report("HOT_RELOAD_DEFERRED pending-inflation")
                return@run false
            }
            retiring = true
            preferenceListener?.let { preferences?.unregisterOnSharedPreferenceChangeListener(it) }
            preferenceListener = null
            preferences = null
            // 仅传递宿主/系统对象和基础类型，不把旧模块对象带到新的 ClassLoader。
            param.setSavedInstanceState(hashMapOf("classLoader" to targetClassLoader, "ime" to imeState))
            imeHook = null
            report("HOT_RELOAD_PREPARED process=$processName")
            true
        }
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        hooks = ReloadableHooks(this, param.oldHookHandles)
        try {
            initialize(param)
            val state = param.savedInstanceState as? Map<*, *>
            targetClassLoader = state?.get("classLoader") as? ClassLoader
            if (param.isSystemServer) {
                installSystemHooks(checkNotNull(targetClassLoader) { "System classloader missing" })
            } else if (processName == HOST_PACKAGE) {
                MainThread.run {
                    installImeHooks(checkNotNull(targetClassLoader) { "IME classloader missing" })
                    imeHook?.restoreAfterReload(state?.get("ime") as? Map<*, *>)
                }
            }
            hooks.removeObsolete()
            report("HOT_RELOAD_READY process=$processName")
        } catch (error: Throwable) {
            retiring = true
            preferenceListener?.let { preferences?.unregisterOnSharedPreferenceChangeListener(it) }
            MainThread.run { imeHook?.prepareForReload() }
            hooks.rollback()
            report("HOT_RELOAD_FAILED type=${error.javaClass.simpleName}")
            throw error
        }
    }

    private fun report(message: String) {
        log(Log.INFO, TAG, message)
        Log.i(TAG, message)
    }

    companion object {
        const val TAG = "XiaoAiInline"
        const val PREFERENCES = "inline"
        const val ENABLED = "enabled"
        const val HOST_PACKAGE = "com.xiaomi.type"
        const val HOST_SERVICE = "com.mi.ime.MiInputMethodService"
    }
}
