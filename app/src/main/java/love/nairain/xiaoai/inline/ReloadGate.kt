package love.nairain.xiaoai.inline

// 无法撤销系统的 inflate 回调；所有回调结束前拒绝退役这一代模块。
internal class ReloadGate {
    var retired = false
        private set
    private var pending = 0

    fun begin(): Boolean {
        if (retired) return false
        pending++
        return true
    }

    fun end() {
        check(pending > 0)
        pending--
    }

    fun retire(): Boolean {
        if (pending != 0) return false
        retired = true
        return true
    }
}
