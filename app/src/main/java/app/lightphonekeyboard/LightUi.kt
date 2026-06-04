package app.lightphonekeyboard

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A small UI kit that gives the settings screens the LightOS look of the vandamd/light-template:
 * a back-arrow header, big text in the Light Phone's system font (Public Sans), each setting shown as a
 * small label above a large current value (tap to change), nav items as plain big text, and generous
 * spacing on black. A short grey hint may sit under an item. Built in code, like the rest of the app.
 *
 * Sizes are a little smaller than the template's (this is a phone-settings list, not a launcher), but
 * keep its proportions: small label ≈ 15sp, big value/label ≈ 26sp, header ≈ 22sp.
 */
object LightUi {
    private const val BIG = 26f
    private const val SMALL = 15f
    private const val HINT = 13f
    private const val TITLE = 22f
    private const val GAP_DP = 26f          // space above each item
    private const val SIDE_DP = 26f         // content side padding

    private fun Activity.px(dp: Float) = (dp * resources.displayMetrics.density).toInt()
    private fun Activity.ripple(): Int =
        TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId

    /**
     * Build a screen: a header ([title], with a left back arrow unless [showBack] is false) over a
     * scrolling content column. [build] adds rows to that column (use [navItem] / [valueItem] / [hint]).
     * Returns the root view for setContentView.
     */
    fun screen(activity: Activity, title: String, showBack: Boolean = true, build: (LinearLayout) -> Unit): View {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.px(SIDE_DP), activity.px(8f), activity.px(SIDE_DP), activity.px(40f))
        }
        build(content)
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.black))
            addView(header(activity, title, showBack), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun header(activity: Activity, title: String, showBack: Boolean): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.px(14f), activity.px(10f), activity.px(18f), activity.px(8f))
        }
        if (showBack) {
            bar.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(activity.getColor(R.color.white))
                val s = activity.px(30f)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setPadding(0, 0, activity.px(6f), 0)
                isClickable = true
                setBackgroundResource(activity.ripple())
                setOnClickListener { activity.finish() }
            })
        } else {
            bar.addView(View(activity), LinearLayout.LayoutParams(activity.px(6f), activity.px(1f)))
        }
        bar.addView(TextView(activity).apply {
            text = title
            textSize = TITLE
            setTextColor(activity.getColor(R.color.white))
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    /** A big-text item that opens another screen / runs an action when tapped. A right-hand chevron
     *  marks it as navigational (vs. the value items, which change in place). */
    fun navItem(parent: LinearLayout, text: String, hint: String? = null, onClick: () -> Unit) {
        val a = parent.context as Activity
        val titleRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bigText(a, text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(a).apply {
                this.text = "›"          // this.text — 'text' alone binds to the val parameter above
                textSize = BIG
                setTextColor(a.getColor(R.color.gray))
                setPadding(a.px(8f), 0, 0, 0)
            })
        }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundResource(a.ripple())
            setOnClickListener { onClick() }
            addView(titleRow)
            if (hint != null) addView(hintText(a, hint))
        }
        parent.addView(row, gapParams(a))
    }

    /**
     * A setting shown as a small label over its big current [value]. Tapping runs [onClick] (toggle or
     * cycle) and then re-reads [value] to refresh the display.
     */
    fun valueItem(parent: LinearLayout, label: String, hint: String? = null, value: () -> String, onClick: () -> Unit) {
        val a = parent.context as Activity
        val labelView = TextView(a).apply {
            text = label
            textSize = SMALL
            setTextColor(a.getColor(R.color.gray))
            maxLines = 1
        }
        val valueView = bigText(a, value())
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundResource(a.ripple())
            setOnClickListener { onClick(); valueView.text = value() }
            addView(labelView)
            addView(valueView)
        }
        if (hint != null) row.addView(hintText(a, hint))
        parent.addView(row, gapParams(a))
    }

    /** A standalone grey hint / paragraph (e.g. the title blurb or a tip). */
    fun hint(parent: LinearLayout, text: String) {
        val a = parent.context as Activity
        parent.addView(hintText(a, text), gapParams(a))
    }

    private fun bigText(a: Activity, text: String) = TextView(a).apply {
        this.text = text
        textSize = BIG
        setTextColor(a.getColor(R.color.white))
        maxLines = 2
    }

    private fun hintText(a: Activity, text: String) = TextView(a).apply {
        this.text = text
        textSize = HINT
        setTextColor(a.getColor(R.color.gray))
        setPadding(0, a.px(4f), 0, 0)
    }

    private fun gapParams(a: Activity) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = a.px(GAP_DP) }
}
