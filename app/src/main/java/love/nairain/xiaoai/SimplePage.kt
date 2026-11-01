package love.nairain.xiaoai

import android.app.Activity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class SimplePage(private val activity: Activity, title: String? = null) {
    val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + maxOf(bars.bottom, ime.bottom))
                insets
            }
        }
        activity.setContentView(scroll)
        title?.let { text(it).textSize = 26f }
    }

    fun text(value: String): TextView = TextView(activity).apply {
        text = value
        textSize = 16f
        setPadding(0, 12, 0, 16)
        column.addView(this)
    }

    fun button(label: String, onClick: () -> Unit): Button = Button(activity).apply {
        text = label
        setOnClickListener { onClick() }
        column.addView(this)
    }
}
