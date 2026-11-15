package fr.perso.minilauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Menu radial en coin bas-droit, piloté comme un joystick :
 * - pousser vers l'extérieur : affiche les groupes, puis ouvre le groupe visé ;
 * - pousser vers l'intérieur (ou vers la flèche du coin) : referme le groupe ;
 * - glisser le long de l'arc : choisit l'appli (défilement infini aux extrémités) ;
 * - relâcher sur une appli : la lance.
 */
class RadialMenuView(context: Context) : View(context) {

    interface Host {
        fun groups(): List<String>
        fun appsIn(group: String): List<AppEntry>
        fun launch(app: AppEntry)
        fun launchLast()
    }

    private enum class Mode { IDLE, GROUPS, APPS }

    var host: Host? = null
    var sounds: Sounds? = null
    var hapticEnabled: Boolean = true

    /** Vrai si le dernier geste a commencé sur la poignée (sert à ignorer le swipe de recherche). */
    var gestureWasRadial: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private val accent = Color.parseColor("#7CC4FF")
    private val accentFill = Color.parseColor("#0B2233")
    private val itemFill = Color.parseColor("#141414")
    private val itemStroke = Color.parseColor("#3A3A3A")
    private val grey = Color.parseColor("#A0A0A0")

    private var cx = 0f
    private var cy = 0f

    private var mode = Mode.IDLE
    private var touching = false
    private var thumbX = 0f
    private var thumbY = 0f

    private var groupList: List<String> = emptyList()
    private var hoverGroup = -1
    private var openGroupIdx = -1

    private var apps: List<AppEntry> = emptyList()
    private var offset = 0
    private var hoverSlot = 0
    private var edgeDir = 0
    private var edgeOver = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val edgeRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.APPS || edgeDir == 0 || apps.isEmpty()) return
            offset = Math.floorMod(offset + edgeDir, apps.size)
            sounds?.tick()
            haptic()
            invalidate()
            handler.postDelayed(this, edgeDelay())
        }
    }

    private fun edgeDelay(): Long {
        val t = edgeOver.coerceIn(0f, 25f) / 25f
        return (260f - t * 170f).toLong()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    // ------------------------------------------------------------------ géométrie

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w - dp(84f)
        cy = h - dp(84f)
    }

    private val ringGroups get() = dp(105f)
    private val ringApps get() = dp(185f)

    private fun px(angle: Float, r: Float): Float = cx + r * cos(Math.toRadians(angle.toDouble())).toFloat()
    private fun py(angle: Float, r: Float): Float = cy - r * sin(Math.toRadians(angle.toDouble())).toFloat()

    private fun groupStep(n: Int): Float = if (n <= 1) 0f else min(25f, 75f / (n - 1))
    private fun groupAngle(i: Int, n: Int): Float = 137.5f - groupStep(n) * (n - 1) / 2f + groupStep(n) * i

    private fun slotAngle(k: Int, m: Int): Float = 129f - 17f * (m - 1) / 2f + 17f * k

    private fun nearestGroup(theta: Float): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for (i in groupList.indices) {
            val d = Math.abs(theta - groupAngle(i, groupList.size))
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    // ------------------------------------------------------------------ gestes

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - cx, e.y - cy) > dp(56f)) {
                    gestureWasRadial = false
                    return false
                }
                gestureWasRadial = true
                touching = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (touching) update(e.x, e.y)
            MotionEvent.ACTION_UP -> if (touching) release()
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return touching
    }

    private fun update(x: Float, y: Float) {
        thumbX = x
        thumbY = y
        val dx = x - cx
        val dy = y - cy
        val r = hypot(dx, dy)
        var th = Math.toDegrees(atan2((-dy).toDouble(), dx.toDouble())).toFloat()
        if (th < 0f) th += 360f
        val towardCorner = dx > dp(8f) && dy > dp(8f)

        if (mode == Mode.IDLE && r > dp(34f)) {
            groupList = host?.groups() ?: emptyList()
            hoverGroup = -1
            mode = Mode.GROUPS
        }

        if (mode == Mode.GROUPS) {
            if (r < dp(34f)) {
                mode = Mode.IDLE
            } else {
                if (!towardCorner && groupList.isNotEmpty()) {
                    val h = nearestGroup(th)
                    if (h != hoverGroup) {
                        hoverGroup = h
                        sounds?.tick()
                        haptic()
                    }
                }
                if (r > dp(140f) && hoverGroup in groupList.indices) openGroup(hoverGroup, th)
            }
        } else if (mode == Mode.APPS) {
            if (r < dp(115f) || towardCorner) closeGroup() else updateApps(th)
        }
        invalidate()
    }

    private fun openGroup(idx: Int, th: Float) {
        val list = host?.appsIn(groupList[idx]) ?: emptyList()
        if (list.isEmpty()) return
        apps = list
        openGroupIdx = idx
        offset = 0
        hoverSlot = 0
        edgeDir = 0
        mode = Mode.APPS
        sounds?.open()
        haptic()
        updateApps(th)
    }

    private fun closeGroup() {
        stopEdge()
        mode = Mode.GROUPS
        hoverGroup = openGroupIdx
        sounds?.close()
        haptic()
    }

    private fun updateApps(th: Float) {
        val n = apps.size
        if (n == 0) return
        val m = min(n, 5)
        val a0 = slotAngle(0, m)
        val aLast = slotAngle(m - 1, m)
        val slot = ((th - a0) / 17f).roundToInt().coerceIn(0, m - 1)
        if (slot != hoverSlot) {
            hoverSlot = slot
            sounds?.tick()
            haptic()
        }
        var dir = 0
        var over = 0f
        if (n > m) {
            if (th < a0 - 4f) { dir = -1; over = a0 - th }
            else if (th > aLast + 4f) { dir = 1; over = th - aLast }
        }
        edgeOver = over
        if (dir != edgeDir) {
            edgeDir = dir
            handler.removeCallbacks(edgeRunnable)
            if (dir != 0) handler.postDelayed(edgeRunnable, edgeDelay())
        }
    }

    private fun stopEdge() {
        edgeDir = 0
        handler.removeCallbacks(edgeRunnable)
    }

    private fun release() {
        val r = hypot(thumbX - cx, thumbY - cy)
        val m = mode
        val app = if (m == Mode.APPS && apps.isNotEmpty())
            apps[Math.floorMod(offset + hoverSlot, apps.size)] else null
        reset()
        when {
            app != null -> host?.launch(app)
            m == Mode.GROUPS && r < dp(80f) -> host?.launchLast()
        }
    }

    private fun reset() {
        stopEdge()
        touching = false
        mode = Mode.IDLE
        hoverGroup = -1
        openGroupIdx = -1
        invalidate()
    }

    private fun haptic() {
        if (hapticEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // ------------------------------------------------------------------ dessin

    override fun onDraw(c: Canvas) {
        if (mode == Mode.IDLE) {
            drawRestHandle(c)
            return
        }
        c.drawColor(Color.argb(150, 0, 0, 0))

        val groupsAlpha = if (mode == Mode.APPS) 80 else 255
        drawGroupsRing(c, groupsAlpha)
        if (mode == Mode.APPS) {
            drawAppsRing(c)
            drawBackArrow(c)
        } else if (hoverGroup in groupList.indices) {
            drawCenterText(c, groupList[hoverGroup], "${host?.appsIn(groupList[hoverGroup])?.size ?: 0} applis")
        }
        drawHandle(c)
    }

    private fun drawRestHandle(c: Canvas) {
        stroke.color = itemStroke
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = null
        c.drawCircle(cx, cy, dp(24f), stroke)
        fill.color = accent
        fill.alpha = 200
        c.drawCircle(cx, cy, dp(3.5f), fill)
    }

    private fun dottedArc(c: Canvas, r: Float) {
        stroke.color = Color.parseColor("#2A2A2A")
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(6f)), 0f)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(arcRect, 180f, 90f, false, stroke)
        stroke.pathEffect = null
    }

    private fun drawGroupsRing(c: Canvas, alpha: Int) {
        dottedArc(c, ringGroups)
        val n = groupList.size
        for (i in 0 until n) {
            val a = groupAngle(i, n)
            val x = px(a, ringGroups)
            val y = py(a, ringGroups)
            val selected = if (mode == Mode.APPS) i == openGroupIdx else i == hoverGroup
            val rad = if (selected) dp(25f) else dp(22f)
            drawItem(c, x, y, rad, selected, alpha, null, groupList[i].take(1).uppercase())
            if (selected && mode == Mode.GROUPS) {
                label(c, groupList[i], x - rad - dp(10f), y + dp(7f))
            }
        }
    }

    private fun drawAppsRing(c: Canvas) {
        dottedArc(c, ringApps)
        val n = apps.size
        if (n == 0) return
        val m = min(n, 5)
        for (k in 0 until m) {
            val a = slotAngle(k, m)
            val x = px(a, ringApps)
            val y = py(a, ringApps)
            val app = apps[Math.floorMod(offset + k, n)]
            val selected = k == hoverSlot
            drawItem(c, x, y, if (selected) dp(28f) else dp(22f), selected, 255, app.icon, app.label.take(1).uppercase())
        }
        if (n > m) { // applis voisines estompées : la liste continue
            val aPrev = slotAngle(0, m) - 17f
            val aNext = slotAngle(m - 1, m) + 17f
            val prev = apps[Math.floorMod(offset - 1, n)]
            val next = apps[Math.floorMod(offset + m, n)]
            drawItem(c, px(aPrev, ringApps), py(aPrev, ringApps), dp(22f), false, 80, prev.icon, prev.label.take(1).uppercase())
            drawItem(c, px(aNext, ringApps), py(aNext, ringApps), dp(22f), false, 80, next.icon, next.label.take(1).uppercase())
        }
        val sel = apps[Math.floorMod(offset + hoverSlot, n)]
        val a = slotAngle(hoverSlot, m)
        label(c, sel.label, px(a, ringApps) - dp(38f), py(a, ringApps) + dp(7f))

        // grande icône + nom au centre de l'écran
        val ccx = width / 2f
        val ccy = height * 0.40f
        drawItem(c, ccx, ccy, dp(56f), true, 255, sel.icon, sel.label.take(1).uppercase())
        text.reset(); text.isAntiAlias = true
        text.color = Color.parseColor("#F2F2F2")
        text.textSize = sp(32f)
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        c.drawText(sel.label, ccx, ccy + dp(56f) + sp(40f), text)
        text.color = grey
        text.textSize = sp(14f)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val idx = Math.floorMod(offset + hoverSlot, n) + 1
        c.drawText("${groupList.getOrNull(openGroupIdx) ?: ""} · $idx sur $n", ccx, ccy + dp(56f) + sp(40f) + sp(24f), text)
    }

    private fun drawCenterText(c: Canvas, title: String, sub: String) {
        val ccx = width / 2f
        val ccy = height * 0.40f
        text.reset(); text.isAntiAlias = true
        text.color = Color.parseColor("#F2F2F2")
        text.textSize = sp(34f)
        text.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        c.drawText(title, ccx, ccy, text)
        text.color = grey
        text.textSize = sp(14f)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(sub, ccx, ccy + sp(26f), text)
    }

    private fun drawBackArrow(c: Canvas) {
        stroke.color = accent
        stroke.alpha = 150
        stroke.strokeWidth = dp(1.5f)
        val r = dp(46f)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(arcRect, 10f, 70f, false, stroke)
        stroke.color = accent
        stroke.strokeWidth = dp(2f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val p = Path()
        p.moveTo(cx + dp(16f), cy + dp(16f)); p.lineTo(cx + dp(36f), cy + dp(36f))
        p.moveTo(cx + dp(36f), cy + dp(24f)); p.lineTo(cx + dp(36f), cy + dp(36f)); p.lineTo(cx + dp(24f), cy + dp(36f))
        c.drawPath(p, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    private fun drawHandle(c: Canvas) {
        var hx = thumbX
        var hy = thumbY
        val dx = hx - cx
        val dy = hy - cy
        val r = hypot(dx, dy)
        val maxR = dp(40f)
        if (r > maxR) { hx = cx + dx * maxR / r; hy = cy + dy * maxR / r }
        // position de repos
        stroke.color = itemStroke
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
        c.drawCircle(cx, cy, dp(28f), stroke)
        stroke.pathEffect = null
        fill.color = Color.parseColor("#1E1E1E"); fill.alpha = 255
        c.drawCircle(hx, hy, dp(28f), fill)
        stroke.color = Color.parseColor("#4A4A4A")
        c.drawCircle(hx, hy, dp(28f), stroke)
        fill.color = accent; fill.alpha = 255
        c.drawCircle(hx, hy, dp(6f), fill)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float) {
        text.reset(); text.isAntiAlias = true
        text.color = accent
        text.textSize = sp(20f)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(s, x, y, text)
    }

    private fun drawItem(c: Canvas, x: Float, y: Float, rad: Float, selected: Boolean, alpha: Int,
                         icon: Drawable?, letter: String) {
        fill.color = if (selected) accentFill else itemFill
        fill.alpha = alpha
        c.drawCircle(x, y, rad, fill)
        stroke.color = if (selected) accent else itemStroke
        stroke.alpha = alpha
        stroke.strokeWidth = dp(if (selected) 1.5f else 1f)
        stroke.pathEffect = null
        c.drawCircle(x, y, rad, stroke)
        if (icon != null) {
            val ir = rad - dp(5f)
            c.save()
            val clip = Path()
            clip.addCircle(x, y, ir, Path.Direction.CW)
            c.clipPath(clip)
            icon.setBounds((x - ir).toInt(), (y - ir).toInt(), (x + ir).toInt(), (y + ir).toInt())
            icon.alpha = alpha
            icon.draw(c)
            icon.alpha = 255
            c.restore()
        } else {
            text.reset(); text.isAntiAlias = true
            text.color = if (selected) accent else Color.parseColor("#D8D8D8")
            text.alpha = alpha
            text.textSize = rad * 0.8f
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textAlign = Paint.Align.CENTER
            c.drawText(letter, x, y + rad * 0.28f, text)
        }
    }
}
