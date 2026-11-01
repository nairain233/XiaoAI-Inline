package love.nairain.xiaoai.inline

// 输入会话仍在时，暂时隐藏键盘不应丢弃解锁后提前到达的响应。
internal class SuggestionDelivery<T> {
    var active = false
        private set
    var visible = false
        private set
    private var pending: List<T>? = null

    fun startInput() {
        active = true
        clearPending()
    }

    fun startView() {
        active = true
        visible = true
    }

    fun hideView(finishingInput: Boolean = false) {
        visible = false
        if (finishingInput) finishInput()
    }

    fun finishInput() {
        active = false
        visible = false
        clearPending()
    }

    fun restoreView(wasVisible: Boolean) {
        // 热重载只恢复窗口状态，不接受旧代请求的响应。
        finishInput()
        visible = wasVisible
    }

    fun defer(suggestions: List<T>) {
        if (active) pending = suggestions
    }

    fun takePending(): List<T>? {
        if (!active || !visible) return null
        return pending.also { clearPending() }
    }

    fun clearPending() {
        pending = null
    }
}
