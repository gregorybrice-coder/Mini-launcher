package fr.perso.minilauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Jauge circulaire sobre : anneau de progression, valeur au centre, légende dessous. */
class GaugeView(context: Context) : View(context) {

    var fraction = 0f
    var centerText = ""
    var subText = ""        // petite ligne sous la valeur (unité)
    var label = ""
    var color = Color.parseColor("#7CC4FF")
    var dimWhenEmpty = false

    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension((78 * d).toInt(), (100 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val size = 68f * d
        val cx = width / 2f
        val cy = size / 2f + 2f * d
        val stroke = 6f * d
        r.set(cx - size / 2 + stroke / 2, cy - size / 2 + stroke / 2, cx + size / 2 - stroke / 2, cy + size / 2 - stroke / 2)

        p.style = Paint.Style.STROKE
        p.strokeWidth = stroke
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.parseColor("#262626")
        c.drawArc(r, 0f, 360f, false, p)

        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.005f) {
            p.color = color
            c.drawArc(r, -90f, 360f * f, false, p)
        }

        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = Color.parseColor("#F2F2F2")
        p.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        var ts = 22f * sd
        p.textSize = ts
        while (p.measureText(centerText) > size * 0.62f && ts > 10f * sd) { ts -= sd; p.textSize = ts }
        val shift = if (subText.isEmpty()) ts * 0.35f else ts * 0.1f
        c.drawText(centerText, cx, cy + shift, p)
        if (subText.isNotEmpty()) {
            p.textSize = 10f * sd
            p.color = Color.parseColor("#9A9A9A")
            c.drawText(subText, cx, cy + ts * 0.1f + 12f * sd, p)
        }

        p.textSize = 12f * sd
        p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        p.color = Color.parseColor("#B0B0B0")
        c.drawText(label, cx, size + 20f * d, p)
    }
}
