package fr.perso.minilauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
                c.drawRect(0f, 0f, w, h, p)
            }
            2 -> { // lignes horizontales fines
                p.color = withAlpha(line, 22)
                var y = 0f
                while (y < h) { c.drawLine(0f, y, w, y, p); y += 16f * d * k }
            }
            3 -> { // grille de points
                p.style = Paint.Style.FILL
                p.color = withAlpha(line, 46)
                val step = 24f * d * k
                var y = step / 2
                while (y < h) {
                    var x = step / 2
                    while (x < w) { c.drawCircle(x, y, 1.1f * d * k.coerceAtLeast(0.6f), p); x += step }
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
                var x = -h
                while (x < w) { c.drawLine(x, h, x + h, 0f, p); x += 20f * d * k }
            }
        }
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
