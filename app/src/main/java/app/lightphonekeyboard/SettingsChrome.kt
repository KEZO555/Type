package app.lightphonekeyboard

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared chrome for the app's deep settings screens (Languages, My words, Emoji): wraps the screen's
 * scrollable [body] with a fixed top bar holding a right-aligned Back button that closes the screen.
 * The setup screen is the app's root, so it doesn't use this.
 */
fun AppCompatActivity.withBackBar(body: View): View {
    val d = resources.displayMetrics.density
    val back = TextView(this).apply {
        text = getString(R.string.back)
        textSize = 17f
        setTextColor(getColor(R.color.white))
        gravity = Gravity.CENTER
        val px = (20 * d).toInt()
        val py = (14 * d).toInt()
        setPadding(px, py, px, py)
        isClickable = true
        isFocusable = true
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { finish() }
    }
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(back)
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(getColor(R.color.black))
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
}
