package love.nairain.xiaoai

import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object MainThread {
    fun <T> run(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val startLock = Any()
        var started = false
        var cancelled = false
        val task = FutureTask {
            synchronized(startLock) {
                check(!cancelled) { "Main looper busy" }
                started = true
            }
            action()
        }
        val handler = Handler(Looper.getMainLooper())
        check(handler.post(task)) { "Main looper unavailable" }
        try {
            return task.get(5, TimeUnit.SECONDS)
        } catch (error: TimeoutException) {
            synchronized(startLock) {
                if (!started) {
                    cancelled = true
                    task.cancel(false)
                    handler.removeCallbacks(task)
                    throw error
                }
            }
            // 已开始的 UI 清理必须真正完成，不能在其仍执行时允许旧代码退役。
            return task.get()
        }
    }
}
