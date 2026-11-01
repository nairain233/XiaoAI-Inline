package love.nairain.xiaoai.inline

internal data class AutofillEditor(val packageName: String, val fieldId: Int, val inputType: Int)

// 认证入口返回但未选取密码时，旧响应可能仍显示锁定状态；只为原输入框补一次请求。
internal class AuthenticationRefresh {
    private var origin: AutofillEditor? = null
    private var clickedAt = 0L
    private var leftEditor = false

    fun clicked(editor: AutofillEditor, now: Long) {
        origin = editor
        clickedAt = now
        leftEditor = false
    }

    fun left() {
        if (origin != null) leftEditor = true
    }

    fun startInput(editor: AutofillEditor?) {
        val previous = origin ?: return
        if (editor == previous || editor == null) return
        // 可以经过密码管理器的输入框，但不能把补请求带到原应用的另一个输入框。
        if (editor.packageName == previous.packageName) cancel() else left()
    }

    fun receivedCredentials(editor: AutofillEditor?) {
        if (editor == origin) cancel()
    }

    fun needsRefresh(editor: AutofillEditor?, now: Long): Boolean {
        if (now - clickedAt !in 0..MAX_RETURN_DELAY_MS) cancel()
        return origin != null && origin == editor && leftEditor
    }

    fun consume(editor: AutofillEditor?, now: Long): Boolean {
        if (!needsRefresh(editor, now)) return false
        cancel()
        return true
    }

    fun cancel() {
        origin = null
        leftEditor = false
    }

    companion object {
        private const val MAX_RETURN_DELAY_MS = 5 * 60 * 1000L
    }
}
