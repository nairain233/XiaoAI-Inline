package love.nairain.xiaoai

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import io.github.libxposed.service.XposedService

@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity(), App.ServiceStateListener {
    private lateinit var status: TextView
    private lateinit var enabledSwitch: Switch
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = SimplePage(this, "小爱内联填充")
        page.text("将密码管理器的自动填充建议显示在小爱键盘内部。")
        status = page.text("正在连接框架…")
        enabledSwitch = Switch(this).apply {
            text = "启用键盘内联填充"
            isEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    try {
                        App.mService?.getRemotePreferences("inline")?.edit()?.putBoolean("enabled", checked)?.apply()
                    } catch (_: Exception) {
                        status.text = "设置保存失败，请刷新框架连接。"
                    }
                }
            }
        }
        page.column.addView(enabledSwitch)
        page.text("在 LSPosed 中启用本模块，作用域勾选「系统框架」和「超级小爱输入法」。首次启用后需要重启手机。\n\n将密码管理器设为系统自动填充服务，并在密码管理器中选择键盘内联展示。")
        page.button("刷新连接状态") { onServiceStateChanged(App.mService) }
        page.button("打开自动填充测试") { startActivity(Intent(this, AutofillTestActivity::class.java)) }
        page.text("首次适配：Android 17 / HyperOS 4 / 小爱 0.2.1246。模块不会读取密码库或记录填充内容。")
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
                if (service == null) {
                    status.text = "尚未连接 LSPosed，请确认模块已启用。"
                } else {
                    enabledSwitch.isChecked = service.getRemotePreferences("inline").getBoolean("enabled", true)
                    val targets = if (service.apiVersion >= 102) service.runningTargets.map { it.processName } else emptyList()
                    status.text = "${service.frameworkName} ${service.frameworkVersion}\nAPI ${service.apiVersion}\n作用域：${service.scope.joinToString()}\n已加载进程：${targets.joinToString().ifEmpty { "尚未加载" }}\n\n已加载不代表填充已通过验证，请完成下方测试。"
                }
            } catch (_: Exception) {
                enabledSwitch.isEnabled = false
                status.text = "框架服务暂不可用，请稍后刷新。"
            } finally {
                updating = false
            }
        }
    }
}
