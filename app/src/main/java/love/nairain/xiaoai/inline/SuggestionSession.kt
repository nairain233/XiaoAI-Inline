package love.nairain.xiaoai.inline

// 收起状态属于当前输入会话，普通响应和视图重建都不能解除它。
internal class SuggestionSession {
    private val epoch = SuggestionEpoch()
    var dismissed = false
        private set

    fun reset() {
        dismissed = false
        invalidate()
    }

    fun dismiss() {
        dismissed = true
        invalidate()
    }

    fun invalidate() {
        epoch.next()
    }

    fun nextResponse(): Long? = if (dismissed) null else epoch.next()

    fun accepts(token: Long): Boolean = !dismissed && epoch.isCurrent(token)
}
