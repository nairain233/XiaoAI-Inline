package love.nairain.xiaoai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult

@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity(), App.ServiceStateListener {
    private lateinit var status: TextView
    private lateinit var enabledSwitch: Switch
    private lateinit var reloadButton: Button
    private lateinit var reloadStatus: TextView
    private var reloadInProgress = false
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = SimplePage(this)
        status = page.text("正在连接框架…")
        enabledSwitch = Switch(this).apply {
            text = "启用键盘内联填充"
            isEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    try {
                        App.mService?.getRemotePreferences("inline")?.edit()?.putBoolean("enabled", checked)?.apply()
                    } catch (_: Exception) {
                        status.text = "设置保存失败"
                    }
                }
            }
        }
        page.column.addView(enabledSwitch)
        page.button("刷新连接状态") { onServiceStateChanged(App.mService) }
        reloadButton = page.button("热重载已更新的模块") { hotReload() }.apply { isEnabled = false }
        reloadStatus = page.text("").apply { visibility = View.GONE }
        page.button("打开自动填充测试") { startActivity(Intent(this, AutofillTestActivity::class.java)) }
        addLauncherIconSwitch(page)
    }

    private fun addLauncherIconSwitch(page: SimplePage) {
        val launcher = ComponentName(this, "$packageName.Launcher")
        var updatingIcon = false
        val hideIconSwitch = Switch(this).apply {
            text = "隐藏桌面图标"
            isChecked = packageManager.getComponentEnabledSetting(launcher) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            setOnCheckedChangeListener { button, hidden ->
                if (updatingIcon) return@setOnCheckedChangeListener
                try {
                    packageManager.setComponentEnabledSetting(
                        launcher,
                        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {
                    updatingIcon = true
                    button.isChecked = !hidden
                    updatingIcon = false
                    Toast.makeText(this@MainActivity, "桌面图标设置失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
        page.column.addView(hideIconSwitch)
    }

    override fun onStart() {
        super.onStart()
        App.addServiceStateListener(this, true)
    }

    override fun onStop() {
        App.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread {
            updating = true
            try {
                enabledSwitch.isEnabled = service != null && service.apiVersion >= 102
                reloadButton.isEnabled = service != null && service.apiVersion >= 102 && !reloadInProgress
                if (service == null) {
                    status.text = "尚未连接 LSPosed"
                } else {
                    enabledSwitch.isChecked = service.getRemotePreferences("inline").getBoolean("enabled", true)
                    val targets = if (service.apiVersion >= 102) service.runningTargets.map {
                        "${it.processName}（${targetState(it.state)}）"
                    } else emptyList()
                    status.text = "${service.frameworkName} ${service.frameworkVersion}\nAPI ${service.apiVersion}\n作用域：${service.scope.joinToString()}\n已加载进程：${targets.joinToString().ifEmpty { "尚未加载" }}"
                }
            } catch (_: Exception) {
                enabledSwitch.isEnabled = false
                reloadButton.isEnabled = false
                status.text = "框架服务暂不可用"
            } finally {
                updating = false
            }
        }
    }

    private fun hotReload() {
        val service = App.mService ?: return
        reloadStatus.visibility = View.VISIBLE
        try {
            if (service.apiVersion < 102) return
            val targets = service.runningTargets.filter {
                it.state == HookedTarget.State.STALE || it.state == HookedTarget.State.FAILED
            }
            if (targets.isEmpty()) {
                reloadStatus.text = "没有需要重载的进程"
                onServiceStateChanged(service)
                return
            }
            reloadInProgress = true
            reloadButton.isEnabled = false
            reloadStatus.text = "正在热重载 ${targets.size} 个进程…"
            var pending = targets.size
            val messages = mutableListOf<String>()
            fun completed(process: String, message: String) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    messages.add("$process：$message")
                    pending--
                    reloadStatus.text = messages.joinToString("\n")
                    if (pending == 0) {
                        reloadInProgress = false
                        onServiceStateChanged(App.mService)
                    }
                }
            }
            targets.forEach { target ->
                try {
                    service.hotReloadModule(target, null) { reloaded, result ->
                        val message = when (result.status()) {
                            HotReloadResult.Status.SUCCEEDED -> "重载成功"
                            HotReloadResult.Status.FAILED -> result.message()
                                ?: "重载失败"
                            HotReloadResult.Status.UNSUPPORTED -> "框架不支持此进程热重载"
                            HotReloadResult.Status.IN_PROGRESS -> "框架正在重载"
                            HotReloadResult.Status.PROCESS_DIED -> "进程已退出"
                        }
                        completed(reloaded.processName, message)
                    }
                } catch (_: Exception) {
                    completed(target.processName, "重载请求提交失败")
                }
            }
        } catch (_: Exception) {
            reloadStatus.text = "读取进程状态失败"
        }
    }

    private fun targetState(state: HookedTarget.State): String = when (state) {
        HookedTarget.State.UP_TO_DATE -> "已是新版"
        HookedTarget.State.STALE -> "待重载"
        HookedTarget.State.RELOADING -> "重载中"
        HookedTarget.State.FAILED -> "重载失败"
    }
}
