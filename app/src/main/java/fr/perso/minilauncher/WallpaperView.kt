package fr.perso.minilauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View

/** Fonds d'écran monochromes très sobres, dessinés en vectoriel (aucune image : pas de poids, net sur tout écran). */
class WallpaperView(context: Context) : View(context) {

    companion object {
        val STYLE_NAMES = listOf("Uni", "Halo", "Lignes", "Points", "Arcs", "Diagonales")
        // fond, trait
        val TINTS = listOf(
            "#090909" to "#FFFFFF", // gris
            "#070B12" to "#7CC4FF", // bleu nuit
            "#080C09" to "#9FD3A8", // vert sauge
            "#0E0C09" to "#E6D5B8"  // sable
        )
        val TINT_NAMES = listOf("Gris", "Bleu nuit", "Vert sauge", "Sable")
    }

    private var style = 0
    private var tint = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    // ---- parallaxe : le fond bouge à l'opposé de l'inclinaison du téléphone ----
    private var parallax = false
    private var amount = 14f // dp
    private var gx0 = 0f
    private var gy0 = 0f
    private var haveBase = false
    private var offX = 0f
    private var offY = 0f
    private var attached = false
    private var listening = false
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val gx = e.values[0]
            val gy = e.values[1]
            if (!haveBase) { gx0 = gx; gy0 = gy; haveBase = true }
            // le « neutre » suit lentement la position tenue : le fond revient au centre si on garde l'inclinaison
            gx0 += (gx - gx0) * 0.004f
            gy0 += (gy - gy0) * 0.004f
            val max = amount * d
            val tx = (((gx - gx0) / 9.81f) * 2.6f * max).coerceIn(-max, max)
            val ty = (-((gy - gy0) / 9.81f) * 2.6f * max).coerceIn(-max, max)
            val nx = offX + (tx - offX) * 0.14f
            val ny = offY + (ty - offY) * 0.14f
            if (Math.abs(nx - offX) > 0.15f || Math.abs(ny - offY) > 0.15f) {
                offX = nx; offY = ny
                invalidate()
            }
        }
    }

    /** Active ou coupe l'effet de profondeur ; `amountDp` = déplacement maximal du fond. */
    fun setParallax(on: Boolean, amountDp: Int) {
        parallax = on
        amount = amountDp.toFloat()
        if (!on) { offX = 0f; offY = 0f; invalidate() }
        updateListening()
    }

    private fun updateListening() {
        val want = parallax && attached && sensor != null
        if (want && !listening) {
            haveBase = false
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            listening = true
        } else if (!want && listening) {
            sm.unregisterListener(listener)
            listening = false
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; updateListening() }
    override fun onDetachedFromWindow() { attached = false; updateListening(); super.onDetachedFromWindow() }

    fun set(style: Int, tint: Int) {
        this.style = Math.floorMod(style, STYLE_NAMES.size)
        this.tint = Math.floorMod(tint, TINTS.size)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val (bg, fg) = TINTS[tint]
        val back = Color.parseColor(bg)
        val line = Color.parseColor(fg)
        c.drawColor(back)
        val w = width.toFloat()
        val h = height.toFloat()
        // le motif est dessiné plus large que l'écran puis décalé : aucun bord vide ne apparaît
        val m = amount * d * 1.2f + 8f
        c.translate(offX, offY)
        // dans les petites vignettes, les motifs sont resserrés pour rester lisibles
        val k = if (h < 400f * d) 0.32f else 1f
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, d * k)
        p.shader = null
        when (style) {
            1 -> { // halo doux en bas à droite
                p.style = Paint.Style.FILL
                p.shader = RadialGradient(w * 0.85f, h * 0.9f, maxOf(w, h) * 0.9f,
                    intArrayOf(withAlpha(line, 40), withAlpha(line, 0)), null, Shader.TileMode.CLAMP)
                c.drawRect(-m, -m, w + m, h + m, p)
            }
            2 -> { // lignes horizontales fines
                p.color = withAlpha(line, 22)
                var y = -m
                while (y < h + m) { c.drawLine(-m, y, w + m, y, p); y += 16f * d * k }
            }
            3 -> { // grille de points
                p.style = Paint.Style.FILL
                p.color = withAlpha(line, 46)
                val step = 24f * d * k
                var y = step / 2 - m
                while (y < h + m) {
                    var x = step / 2 - m
                    while (x < w + m) { c.drawCircle(x, y, 1.1f * d * k.coerceAtLeast(0.6f), p); x += step }
                    y += step
                }
            }
            4 -> { // arcs concentriques depuis le coin : écho du menu radial
                p.color = withAlpha(line, 26)
                var r = 60f * d * k
                while (r < maxOf(w, h) * 1.5f) { c.drawCircle(w, h, r, p); r += 46f * d * k }
            }
            5 -> { // diagonales fines
                p.color = withAlpha(line, 20)
                var x = -h - m
                while (x < w + m) { c.drawLine(x, h + m, x + h + 2 * m, -m, p); x += 20f * d * k }
            }
        }
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
