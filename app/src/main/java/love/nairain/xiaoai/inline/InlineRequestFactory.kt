package love.nairain.xiaoai.inline

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import love.nairain.xiaoai.R
import kotlin.math.roundToInt

internal object InlineRequestFactory {
    const val MAX_SUGGESTIONS = 6
    const val STRIP_HEIGHT_DP = 48
    const val CHIP_HEIGHT_DP = 40
    const val START_PADDING_DP = 8
    const val END_PADDING_DP = 4
    const val DISMISS_WIDTH_DP = 44
    const val DISMISS_GAP_DP = 4
    const val CHIP_GAP_DP = 6

    data class Palette(val background: Int, val foreground: Int, val secondary: Int)

    fun palette(context: Context): Palette = if (isDark(context)) {
        Palette(Color.rgb(24, 25, 27), Color.rgb(245, 245, 246), Color.rgb(183, 186, 193))
    } else {
        Palette(Color.rgb(241, 242, 244), Color.rgb(32, 33, 36), Color.rgb(98, 102, 109))
    }

    // AndroidX 1.1 的公开样式 Builder 继承了带 RestrictTo 的共享基类。
    @SuppressLint("RestrictedApi")
    fun create(context: Context, availableWidth: Int): InlineSuggestionsRequest {
        val colors = palette(context)
        val background = Icon.createWithResource(
            "love.nairain.xiaoai",
            if (isDark(context)) R.drawable.inline_chip_dark else R.drawable.inline_chip_light,
        )
        val padding = context.dp(12)
        // 使用模块自己的资源包名，交由远程渲染进程加载圆角背景。
        val chipStyle = ViewStyle.Builder().setBackground(background)
            .setPadding(padding, 0, padding, 0).build()
        val singleIconStyle = ImageViewStyle.Builder()
            .setMaxWidth(context.dp(20)).setMaxHeight(context.dp(20)).build()
        val startIconStyle = ImageViewStyle.Builder()
            .setMaxWidth(context.dp(20)).setMaxHeight(context.dp(20))
            .setLayoutMargin(0, 0, context.dp(8), 0).build()
        val endIconStyle = ImageViewStyle.Builder()
            .setMaxWidth(context.dp(20)).setMaxHeight(context.dp(20))
            .setLayoutMargin(context.dp(8), 0, 0, 0).build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(chipStyle)
            .setSingleIconChipStyle(chipStyle)
            .setSingleIconChipIconStyle(singleIconStyle)
            .setStartIconStyle(startIconStyle)
            .setEndIconStyle(endIconStyle)
            .setLayoutDirection(context.resources.configuration.layoutDirection)
            .setTitleStyle(TextViewStyle.Builder().setTextColor(colors.foreground).setTextSize(14f)
                .setTypeface("sans-serif-medium", Typeface.NORMAL).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(colors.secondary).setTextSize(11f).build())
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val height = context.dp(CHIP_HEIGHT_DP)
        // 胶囊的最大宽度与宿主滚动区域一致，始终为收起入口预留空间。
        val reserved = context.dp(START_PADDING_DP + END_PADDING_DP + DISMISS_WIDTH_DP + DISMISS_GAP_DP)
        val maximumWidth = (availableWidth - reserved).coerceAtLeast(1)
        val minimumWidth = context.dp(48).coerceAtMost(maximumWidth)
        // 部分服务期望至少三份规格，多余建议按协议复用最后一份。
        val specs = List(3) {
            InlinePresentationSpec.Builder(Size(minimumWidth, height), Size(maximumWidth, height))
                .setStyle(styles).build()
        }
        return InlineSuggestionsRequest.Builder(specs).setMaxSuggestionCount(MAX_SUGGESTIONS).build()
    }

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
