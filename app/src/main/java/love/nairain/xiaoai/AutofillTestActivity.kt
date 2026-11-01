package love.nairain.xiaoai

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("SetTextI18n")
class AutofillTestActivity : Activity() {
    private lateinit var username: ObservedEditText
    private lateinit var password: ObservedEditText
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = SimplePage(this, "自动填充测试")
        page.text("先在当前密码管理器中新建一个登录条目，使用以下虚构测试信息：\n\nURI：androidapp://love.nairain.xiaoai\n用户名：$TEST_USERNAME\n密码：$TEST_PASSWORD\n\n保存后返回这里，点击下方输入框，再点击小爱键盘内的填充建议。")
        username = ObservedEditText(this, "username").apply {
            id = R.id.test_username
            hint = "用户名"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        }
        password = ObservedEditText(this, "password").apply {
            id = R.id.test_password
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        }
        page.column.addView(username, LinearLayout.LayoutParams(-1, -2))
        page.column.addView(password, LinearLayout.LayoutParams(-1, -2))
        result = page.text("等待从键盘内选择建议。请勿手动输入测试值。")
        username.onAutofilled = { updateResult() }
        password.onAutofilled = { updateResult() }
        page.button("请求自动填充建议") {
            val focused = if (password.hasFocus()) password else username
            focused.requestFocus()
            getSystemService(AutofillManager::class.java)?.requestAutofill(focused)
        }
        page.button("检查填充结果") { updateResult() }
        Log.i(TAG, "TEST_FORM_READY")
    }

    private fun updateResult() {
        val usernameMatches = username.text.toString() == TEST_USERNAME
        val passwordMatches = password.text.toString() == TEST_PASSWORD
        val passed = username.autofilled && password.autofilled && usernameMatches && passwordMatches
        result.text = if (passed) {
            "测试通过：用户名和密码均已通过系统自动填充，并与测试条目一致。"
        } else {
            "用户名收到系统填充：${username.autofilled}\n密码收到系统填充：${password.autofilled}\n测试值匹配：${usernameMatches && passwordMatches}"
        }
        Log.i(TAG, "TEST_RESULT usernameAutofilled=${username.autofilled} passwordAutofilled=${password.autofilled} usernameMatches=$usernameMatches passwordMatches=$passwordMatches passed=$passed")
    }

    private class ObservedEditText(activity: Activity, private val role: String) : EditText(activity) {
        var autofilled = false
            private set
        var onAutofilled: (() -> Unit)? = null

        init {
            importantForAutofill = IMPORTANT_FOR_AUTOFILL_YES
            isSaveEnabled = false
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    autofilled = false
                }
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }

        override fun autofill(value: AutofillValue) {
            super.autofill(value)
            autofilled = value.isText
            Log.i(TAG, "FIELD_AUTOFILLED role=$role")
            onAutofilled?.invoke()
        }
    }

    companion object {
        private const val TAG = "XiaoAiInlineTest"
        private const val TEST_USERNAME = "xiaoai-inline-test"
        private const val TEST_PASSWORD = "Inline-Test-102!"
    }
}
