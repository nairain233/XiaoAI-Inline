package love.nairain.xiaoai.inline

internal class SuggestionEpoch {
    private var value = 0L

    fun next(): Long = ++value

    fun isCurrent(token: Long): Boolean = token == value
}
