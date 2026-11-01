package love.nairain.xiaoai.inline

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import love.nairain.xiaoai.inline.InlineRequestFactory.dp

// 只由 Hook 创建，不通过 XML 或布局编辑器实例化。
@SuppressLint("ViewConstructor")
internal class InlineSuggestionHost(
    context: Context,
    private val report: (String) -> Unit,
    onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        isFocusable = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val dismiss = ImageButton(context).apply {
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        contentDescription = "收起自动填充建议"
        tooltipText = contentDescription
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        setOnClickListener { onDismiss() }
    }
    private val clipRect = Rect()
    private val viewportRect = Rect()
    private val drawListener = ViewTreeObserver.OnDrawListener { clipSuggestions() }
    private val layoutListeners = mutableMapOf<View, OnLayoutChangeListener>()
    private var inputVisible = false
    private var hasSuggestions = false

    init {
        visibility = GONE
        isClickable = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setPaddingRelative(context.dp(InlineRequestFactory.START_PADDING_DP), 0,
            context.dp(InlineRequestFactory.END_PADDING_DP), 0)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(scroll, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(dismiss, LinearLayout.LayoutParams(context.dp(InlineRequestFactory.DISMISS_WIDTH_DP),
                context.dp(InlineRequestFactory.DISMISS_WIDTH_DP)).apply {
                marginStart = context.dp(InlineRequestFactory.DISMISS_GAP_DP)
            })
        }
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyAppearance()
    }

    fun attachTo(container: FrameLayout) {
        if (parent === container) return
        (parent as? ViewGroup)?.removeView(this)
        container.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(InlineRequestFactory.STRIP_HEIGHT_DP), Gravity.TOP))
    }

    fun positionAt(windowTop: Int) {
        val container = parent as? View ?: return
        val location = IntArray(2)
        container.getLocationInWindow(location)
        val top = (windowTop - location[1]).coerceAtLeast(0)
        val params = layoutParams as? LayoutParams ?: return
        if (params.topMargin != top) {
            params.topMargin = top
            layoutParams = params
        }
    }

    fun prepare(count: Int): List<FrameLayout> {
        clear()
        applyAppearance()
        return List(count) { index ->
            FrameLayout(context).also { slot ->
                slot.visibility = GONE
                val params = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                if (index < count - 1) params.marginEnd = context.dp(InlineRequestFactory.CHIP_GAP_DP)
                row.addView(slot, params)
            }
        }
    }

    fun showSuggestion(slot: FrameLayout, view: InlineContentView, index: Int) {
        if (slot.parent !== row) return
        // inflate 回调携带远程内容的实际尺寸；WRAP_CONTENT 会丢失它，导致滚动容器内测成零宽。
        val renderedSize = view.layoutParams
        if (renderedSize == null || renderedSize.width <= 0 || renderedSize.height <= 0) {
            report("SUGGESTION_SIZE_INVALID index=$index")
            return
        }
        val layoutListener = object : OnLayoutChangeListener {
            override fun onLayoutChange(
                changedView: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                if (right <= left || bottom <= top) return
                changedView.removeOnLayoutChangeListener(this)
                layoutListeners.remove(changedView)
                val bounds = Rect()
                val visible = changedView.getGlobalVisibleRect(bounds)
                report("SUGGESTION_LAYOUT index=$index width=${right - left} height=${bottom - top} visible=$visible bounds=$bounds")
            }
        }
        layoutListeners[view] = layoutListener
        view.addOnLayoutChangeListener(layoutListener)
        slot.addView(view, LayoutParams(renderedSize).apply { gravity = Gravity.CENTER_VERTICAL })
        slot.visibility = VISIBLE
        hasSuggestions = true
        updateVisibility()
        report("SUGGESTION_VISIBLE index=$index remoteWidth=${renderedSize.width} remoteHeight=${renderedSize.height}")
        // 这是系统提供的点击通知；真正的填充仍由远程建议处理。
        view.setOnClickListener { report("SUGGESTION_CLICK index=$index") }
    }

    fun clear() {
        hasSuggestions = false
        visibility = GONE
        // 远程内容可能仍被系统持有，主动解除其中引用模块代码的监听器。
        layoutListeners.forEach { (view, listener) -> view.removeOnLayoutChangeListener(listener) }
        layoutListeners.clear()
        for (index in 0 until row.childCount) {
            val slot = row.getChildAt(index) as? ViewGroup ?: continue
            slot.getChildAt(0)?.setOnClickListener(null)
        }
        row.removeAllViews()
        scroll.scrollTo(0, 0)
    }

    fun setInputVisible(value: Boolean) {
        inputVisible = value
        updateVisibility()
    }

    private fun updateVisibility() {
        visibility = if (inputVisible && hasSuggestions) VISIBLE else GONE
    }

    private fun applyAppearance() {
        val colors = InlineRequestFactory.palette(context)
        setBackgroundColor(colors.background)
        dismiss.imageTintList = ColorStateList.valueOf(colors.secondary)
        dismiss.background = RippleDrawable(
            ColorStateList.valueOf((colors.foreground and 0x00ffffff) or 0x22000000), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyAppearance()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnDrawListener(drawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnDrawListener(drawListener)
        super.onDetachedFromWindow()
    }

    private fun clipSuggestions() {
        if (visibility != VISIBLE) return
        // 远程 Surface 会位于本地 View 之上，必须把收起按钮排除在裁剪范围之外。
        // getDrawingRect 包含 scrollX/scrollY，转换到祖先坐标后视口不会随内容一起偏移。
        scroll.getDrawingRect(viewportRect)
        offsetDescendantRectToMyCoords(scroll, viewportRect)
        for (index in 0 until row.childCount) {
            val slot = row.getChildAt(index) as? ViewGroup ?: continue
            val inline = slot.getChildAt(0) as? InlineContentView ?: continue
            clipRect.set(viewportRect)
            offsetRectIntoDescendantCoords(inline, clipRect)
            if (!clipRect.intersect(0, 0, inline.width, inline.height)) clipRect.setEmpty()
            if (inline.clipBounds != clipRect) inline.clipBounds = clipRect
        }
    }
}
