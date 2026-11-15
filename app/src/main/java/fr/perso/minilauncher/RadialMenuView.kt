package fr.perso.minilauncher

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Menu radial en coin bas-droit, piloté comme un joystick :
 * - pousser vers l'extérieur : affiche les groupes (segments colorés) puis ouvre le groupe visé ;
 * - dernier segment (engrenage) : les réglages (groupes et raccourcis) ;
 * - pousser vers l'intérieur (ou vers la flèche du coin) : referme le groupe ;
 * - glisser le long de l'arc : choisit l'appli (défilement infini aux extrémités) ;
 * - relâcher sur une appli : la lance.
 * Une démonstration animée montre le geste du pouce.
 */
class RadialMenuView(context: Context) : View(context) {

    interface Host {
        fun groups(): List<String>
        fun groupColor(group: String): Int
        fun appsIn(group: String): List<AppEntry>
        fun launch(app: AppEntry)
        fun launchLast()
        fun openSettings()
        fun menuMarginRight(): Int
        fun menuMarginBottom(): Int
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
    private val itemFill = Color.parseColor("#141414")
    private val itemStroke = Color.parseColor("#3A3A3A")
    private val grey = Color.parseColor("#A0A0A0")
    private val settingsColor = Color.parseColor("#9E9E9E")

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

    // démonstration
    private var demo = false
    private var demoAnim: ValueAnimator? = null
    private var demoCaption = ""
    private var demoPulse = 0f
    private class Kf(val t: Float, val th: Float, val r: Float, val caption: String)
    private var kfs: List<Kf> = emptyList()

    private val snd get() = if (demo) null else sounds

    private val handler = Handler(Looper.getMainLooper())
    private val edgeRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.APPS || edgeDir == 0 || apps.isEmpty()) return
            offset = Math.floorMod(offset + edgeDir, apps.size)
            snd?.tick()
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
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val sectorPath = Path()

    // ------------------------------------------------------------------ géométrie

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeCenter()
    }

    /** Place le centre du menu selon les marges choisies dans les réglages. */
    private fun placeCenter() {
        val mr = host?.menuMarginRight() ?: 154
        val mb = host?.menuMarginBottom() ?: 200
        cx = width - dp(mr.toFloat())
        cy = height - dp(mb.toFloat())
    }

    /** À appeler quand les groupes ou leurs couleurs ont pu changer. */
    fun refresh() {
        if (!touching && !demo) placeCenter()
        if (mode == Mode.IDLE) groupList = host?.groups() ?: emptyList()
        invalidate()
    }

    private val sectorIn get() = dp(58f)
    private val sectorOut get() = dp(118f)
    private val ringApps get() = dp(170f)
    private val openRadius get() = dp(128f)

    // le premier niveau occupe tout le tour sauf le coin sud-est (zone de retour)
    private val START = 4f
    private val END = 266f

    // éventail des applis : centré sur le groupe ouvert, ramené dans l'écran
    private var appsCenter = 129f
    private var armed = false
    private fun clampCenter(a: Float) = a.coerceIn(78f, 192f)

    private fun px(angle: Float, r: Float): Float = cx + r * cos(Math.toRadians(angle.toDouble())).toFloat()
    private fun py(angle: Float, r: Float): Float = cy - r * sin(Math.toRadians(angle.toDouble())).toFloat()

    /** Nombre de segments au premier niveau : les groupes + le segment des réglages. */
    private fun total() = groupList.size + 1
    private fun step() = (END - START) / total()
    private fun itemAngle(i: Int) = START + step() * (i + 0.5f)
    private fun settingsIdx() = groupList.size

    private fun segmentAt(theta: Float): Int {
        var t = theta
        if (t > (END + 360f) / 2f) t -= 360f // juste sous l'horizontale, côté droit : premier segment
        return ((t - START) / step()).toInt().coerceIn(0, total() - 1)
    }

    private fun slotAngle(k: Int, m: Int): Float = appsCenter - 17f * (m - 1) / 2f + 17f * k

    private fun colorOfIdx(i: Int): Int =
        if (i == settingsIdx()) settingsColor else host?.groupColor(groupList[i]) ?: accent

    private fun currentColor(): Int = when {
        mode == Mode.APPS && openGroupIdx in groupList.indices -> colorOfIdx(openGroupIdx)
        mode == Mode.GROUPS && hoverGroup in 0 until total() -> colorOfIdx(hoverGroup)
        else -> accent
    }

    // ------------------------------------------------------------------ gestes

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - cx, e.y - cy) > dp(56f)) {
                    gestureWasRadial = false
                    return false
                }
                stopDemo()
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
                    val h = segmentAt(th)
                    if (h != hoverGroup) {
                        hoverGroup = h
                        snd?.tick()
                        haptic()
                    }
                }
                if (r > openRadius && hoverGroup in groupList.indices) openGroup(hoverGroup, th)
            }
        } else if (mode == Mode.APPS) {
            if (r < dp(100f) || towardCorner) closeGroup() else updateApps(th)
        }
        invalidate()
    }

    private fun openGroup(idx: Int, th: Float) {
        val list = host?.appsIn(groupList[idx]) ?: emptyList()
        if (list.isEmpty()) return
        apps = list
        appsCenter = clampCenter(itemAngle(idx))
        armed = false
        openGroupIdx = idx
        offset = 0
        hoverSlot = 0
        edgeDir = 0
        mode = Mode.APPS
        snd?.open()
        haptic()
        updateApps(th)
    }

    private fun closeGroup() {
        stopEdge()
        mode = Mode.GROUPS
        hoverGroup = openGroupIdx
        snd?.close()
        haptic()
    }

    private fun updateApps(th: Float) {
        val n = apps.size
        if (n == 0) return
        val m = min(n, 5)
        val a0 = slotAngle(0, m)
        val aLast = slotAngle(m - 1, m)
        var d = th - appsCenter
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        val t = appsCenter + d
        if (!armed && t >= a0 - 4f && t <= aLast + 4f) armed = true
        val slot = ((t - a0) / 17f).roundToInt().coerceIn(0, m - 1)
        if (slot != hoverSlot) {
            hoverSlot = slot
            snd?.tick()
            haptic()
        }
        var dir = 0
        var over = 0f
        if (n > m && armed) {
            if (t < a0 - 4f) { dir = -1; over = a0 - t }
            else if (t > aLast + 4f) { dir = 1; over = t - aLast }
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
        val app = if (m == Mode.APPS && armed && apps.isNotEmpty())
            apps[Math.floorMod(offset + hoverSlot, apps.size)] else null
        val onSettings = m == Mode.GROUPS && hoverGroup == settingsIdx() && r >= dp(80f)
        reset()
        when {
            app != null -> host?.launch(app)
            onSettings -> host?.openSettings()
            m == Mode.GROUPS && r < dp(80f) -> host?.launchLast()
        }
    }

    private fun reset() {
        stopEdge()
        touching = false
        mode = Mode.IDLE
        hoverGroup = -1
        openGroupIdx = -1
        groupList = host?.groups() ?: emptyList()
        invalidate()
    }

    private fun haptic() {
        if (hapticEnabled && !demo) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // ------------------------------------------------------------------ démonstration

    /** Joue une animation qui montre comment le pouce doit agir. */
    fun playDemo() {
        if (touching || demo || width == 0) return
        reset()
        val h = host ?: return
        groupList = h.groups()
        if (groupList.isEmpty()) return
        val target = groupList.indices.firstOrNull { h.appsIn(groupList[it]).isNotEmpty() } ?: 0
        val thFirst = itemAngle(0)
        val thTarget = itemAngle(target)
        val thApps = clampCenter(thTarget) // milieu de l'éventail des applis
        kfs = listOf(
            Kf(0f, thFirst, 0f, "Posez le pouce sur le bouton"),
            Kf(900f, thFirst, 0f, "Poussez vers l'extérieur :\nles groupes apparaissent"),
            Kf(2100f, thFirst, 105f, "Glissez pour choisir un groupe"),
            Kf(3000f, thTarget, 105f, "Poussez encore :\nle groupe s'ouvre"),
            Kf(3900f, thTarget, 168f, "Glissez le long de l'arc\npour choisir l'appli"),
            Kf(5300f, thApps, 188f, "Relâchez pour lancer l'appli\n(sans lever le pouce avant)"),
            Kf(6700f, thApps, 188f, "Revenez vers le coin\npour refermer le groupe"),
            Kf(7700f, thApps, 80f, "Relâchez au centre :\ndernière appli utilisée"),
            Kf(8800f, thApps, 0f, "")
        )
        demo = true
        demoAnim?.cancel()
        demoAnim = ValueAnimator.ofFloat(0f, kfs.last().t).apply {
            duration = kfs.last().t.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { a -> demoStep(a.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (demo) endDemo()
                }
            })
            start()
        }
    }

    private fun demoStep(t: Float) {
        var i = 0
        while (i < kfs.size - 2 && t >= kfs[i + 1].t) i++
        val a = kfs[i]
        val b = kfs[i + 1]
        val u = ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f)
        val e = u * u * (3f - 2f * u) // adouci
        val th = a.th + (b.th - a.th) * e
        val r = dp(a.r + (b.r - a.r) * e)
        demoCaption = a.caption
        demoPulse = (t % 900f) / 900f
        update(px(th, r), py(th, r))
        invalidate()
    }

    private fun stopDemo() {
        if (!demo) return
        demoAnim?.cancel()
        endDemo()
    }

    private fun endDemo() {
        demo = false
        demoAnim = null
        demoCaption = ""
        reset()
    }

    // ------------------------------------------------------------------ dessin

    override fun onDraw(c: Canvas) {
        if (mode == Mode.IDLE) {
            if (groupList.isEmpty()) groupList = host?.groups() ?: emptyList()
            drawRestHandle(c)
            if (demo) drawDemo(c, cx, cy)
            return
        }
        c.drawColor(Color.argb(150, 0, 0, 0))

        drawGroupsRing(c)
        if (mode == Mode.APPS) {
            drawAppsRing(c)
            drawBackArrow(c)
        } else if (hoverGroup in 0 until total()) {
            if (hoverGroup == settingsIdx()) {
                drawCenterText(c, "Réglages", "groupes et raccourcis", settingsColor)
            } else {
                drawCenterText(
                    c, groupList[hoverGroup],
                    "${host?.appsIn(groupList[hoverGroup])?.size ?: 0} applis", colorOfIdx(hoverGroup)
                )
            }
        }
        drawHandle(c)
        if (demo) drawDemo(c, thumbX, thumbY)
    }

    /** Bouton au repos : un anneau découpé aux couleurs des groupes. */
    private fun drawRestHandle(c: Canvas) {
        val n = groupList.size
        if (n > 0) {
            val sweep = 360f / n
            stroke.strokeWidth = dp(3.5f)
            stroke.pathEffect = null
            stroke.strokeCap = Paint.Cap.BUTT
            arcRect.set(cx - dp(27f), cy - dp(27f), cx + dp(27f), cy + dp(27f))
            for (i in 0 until n) {
                stroke.color = host?.groupColor(groupList[i]) ?: accent
                stroke.alpha = 230
                c.drawArc(arcRect, -90f + sweep * i + 3f, sweep - 6f, false, stroke)
            }
        } else {
            stroke.color = itemStroke
            stroke.strokeWidth = dp(1f)
            stroke.pathEffect = null
            c.drawCircle(cx, cy, dp(24f), stroke)
        }
        fill.color = Color.parseColor("#1E1E1E"); fill.alpha = 255
        c.drawCircle(cx, cy, dp(20f), fill)
        fill.color = accent
        fill.alpha = 255
        c.drawCircle(cx, cy, dp(5f), fill)
    }

    private fun dottedArc(c: Canvas, r: Float, color: Int, alpha: Int) {
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = dp(1.5f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(6f)), 0f)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(arcRect, -(appsCenter + 48f), 96f, false, stroke)
        stroke.pathEffect = null
    }

    /** Secteur d'anneau entre deux angles (degrés mathématiques) et deux rayons. */
    private fun sector(a1: Float, a2: Float, rIn: Float, rOut: Float): Path {
        outerRect.set(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
        innerRect.set(cx - rIn, cy - rIn, cx + rIn, cy + rIn)
        sectorPath.reset()
        sectorPath.arcTo(outerRect, -a2, a2 - a1, true)
        sectorPath.arcTo(innerRect, -a1, -(a2 - a1), false)
        sectorPath.close()
        return sectorPath
    }

    private fun drawGroupsRing(c: Canvas) {
        val total = total()
        val st = step()
        for (i in 0 until total) {
            val color = colorOfIdx(i)
            val selected = if (mode == Mode.APPS) i == openGroupIdx else i == hoverGroup
            val grow = if (selected && mode == Mode.GROUPS) dp(12f) else 0f
            val a1 = START + st * i + 1.3f
            val a2 = START + st * (i + 1) - 1.3f
            val path = sector(a1, a2, sectorIn, sectorOut + grow)
            val alpha = when {
                mode == Mode.APPS -> if (selected) 210 else 55
                selected -> 255
                else -> 150
            }
            fill.color = color
            fill.alpha = alpha
            c.drawPath(path, fill)
            stroke.color = color
            stroke.alpha = if (mode == Mode.APPS && !selected) 70 else 255
            stroke.strokeWidth = dp(if (selected) 2.5f else 1.2f)
            stroke.pathEffect = null
            c.drawPath(path, stroke)

            val mid = START + st * (i + 0.5f)
            val lx = px(mid, (sectorIn + sectorOut) / 2f + grow / 2f)
            val ly = py(mid, (sectorIn + sectorOut) / 2f + grow / 2f)
            val txtAlpha = if (mode == Mode.APPS && !selected) 90 else 255
            if (i == settingsIdx()) {
                drawGear(c, lx, ly, dp(10f), Color.WHITE, txtAlpha)
            } else {
                text.reset(); text.isAntiAlias = true
                text.color = Color.WHITE
                text.alpha = txtAlpha
                text.textSize = sp(if (selected) 20f else 17f)
                text.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                text.textAlign = Paint.Align.CENTER
                c.drawText(groupList[i].take(1).uppercase(), lx, ly + sp(6f), text)
            }
        }
    }

    private fun drawGear(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        stroke.color = color
        stroke.alpha = alpha
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = dp(2.2f)
        c.drawCircle(x, y, r * 0.55f, stroke)
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            val x1 = x + (r * 0.75f) * cos(a).toFloat()
            val y1 = y + (r * 0.75f) * sin(a).toFloat()
            val x2 = x + r * cos(a).toFloat()
            val y2 = y + r * sin(a).toFloat()
            c.drawLine(x1, y1, x2, y2, stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun dark(color: Int): Int =
        Color.rgb((Color.red(color) * 0.28f).toInt(), (Color.green(color) * 0.28f).toInt(), (Color.blue(color) * 0.28f).toInt())

    private fun drawAppsRing(c: Canvas) {
        val color = currentColor()
        dottedArc(c, ringApps, color, 200)
        val n = apps.size
        if (n == 0) return
        val m = min(n, 5)
        for (k in 0 until m) {
            val a = slotAngle(k, m)
            val x = px(a, ringApps)
            val y = py(a, ringApps)
            val app = apps[Math.floorMod(offset + k, n)]
            val selected = k == hoverSlot
            drawItem(c, x, y, if (selected) dp(28f) else dp(22f), selected, 255, app.icon, app.label.take(1).uppercase(), color)
        }
        if (n > m) { // applis voisines estompées : la liste continue
            val aPrev = slotAngle(0, m) - 17f
            val aNext = slotAngle(m - 1, m) + 17f
            val prev = apps[Math.floorMod(offset - 1, n)]
            val next = apps[Math.floorMod(offset + m, n)]
            drawItem(c, px(aPrev, ringApps), py(aPrev, ringApps), dp(22f), false, 80, prev.icon, prev.label.take(1).uppercase(), color)
            drawItem(c, px(aNext, ringApps), py(aNext, ringApps), dp(22f), false, 80, next.icon, next.label.take(1).uppercase(), color)
        }
        val sel = apps[Math.floorMod(offset + hoverSlot, n)]
        val a = slotAngle(hoverSlot, m)
        label(c, sel.label, px(a, ringApps) - dp(38f), py(a, ringApps) + dp(7f), color)

        // grande icône + nom au centre de l'écran
        val ccx = width / 2f
        val ccy = height * 0.40f
        drawItem(c, ccx, ccy, dp(56f), true, 255, sel.icon, sel.label.take(1).uppercase(), color)
        text.reset(); text.isAntiAlias = true
        text.color = Color.parseColor("#F2F2F2")
        text.textSize = sp(32f)
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        c.drawText(sel.label, ccx, ccy + dp(56f) + sp(40f), text)
        text.color = color
        text.textSize = sp(14f)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val idx = Math.floorMod(offset + hoverSlot, n) + 1
        c.drawText("${groupList.getOrNull(openGroupIdx) ?: ""} · $idx sur $n", ccx, ccy + dp(56f) + sp(40f) + sp(24f), text)
    }

    private fun drawCenterText(c: Canvas, title: String, sub: String, color: Int) {
        val ccx = width / 2f
        val ccy = height * 0.40f
        text.reset(); text.isAntiAlias = true
        text.color = color
        text.textSize = sp(36f)
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        c.drawText(title, ccx, ccy, text)
        text.color = grey
        text.textSize = sp(14f)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(sub, ccx, ccy + sp(26f), text)
    }

    private fun drawBackArrow(c: Canvas) {
        val color = currentColor()
        stroke.color = color
        stroke.alpha = 190
        stroke.strokeWidth = dp(1.5f)
        stroke.pathEffect = null
        val r = dp(46f)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(arcRect, 10f, 70f, false, stroke)
        stroke.color = color
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
        val color = currentColor()
        // position de repos
        stroke.color = itemStroke
        stroke.strokeWidth = dp(1f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
        c.drawCircle(cx, cy, dp(28f), stroke)
        stroke.pathEffect = null
        fill.color = Color.parseColor("#1E1E1E"); fill.alpha = 255
        c.drawCircle(hx, hy, dp(28f), fill)
        stroke.color = color
        stroke.strokeWidth = dp(2f)
        c.drawCircle(hx, hy, dp(28f), stroke)
        fill.color = color; fill.alpha = 255
        c.drawCircle(hx, hy, dp(7f), fill)
    }

    /** Pouce fantôme + légende pendant la démonstration. */
    private fun drawDemo(c: Canvas, x: Float, y: Float) {
        fill.color = Color.WHITE
        fill.alpha = 70
        c.drawCircle(x, y, dp(30f) + dp(8f) * demoPulse, fill)
        fill.alpha = 150
        c.drawCircle(x, y, dp(20f), fill)
        stroke.color = Color.WHITE
        stroke.alpha = 220
        stroke.strokeWidth = dp(2f)
        stroke.pathEffect = null
        c.drawCircle(x, y, dp(20f), stroke)

        if (demoCaption.isEmpty()) return
        text.reset(); text.isAntiAlias = true
        text.color = Color.WHITE
        text.textSize = sp(19f)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        val lines = demoCaption.split("\n")
        val baseY = height * 0.66f
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, text.measureText(l))
        val lh = sp(26f)
        val bg = RectF(width / 2f - maxW / 2f - dp(18f), baseY - sp(22f),
            width / 2f + maxW / 2f + dp(18f), baseY + lh * (lines.size - 1) + sp(12f))
        fill.color = Color.parseColor("#E6121212"); fill.alpha = 235
        c.drawRoundRect(bg, dp(14f), dp(14f), fill)
        for ((k, l) in lines.withIndex()) c.drawText(l, width / 2f, baseY + lh * k, text)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, color: Int) {
        text.reset(); text.isAntiAlias = true
        text.color = color
        text.textSize = sp(20f)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textAlign = Paint.Align.RIGHT
        c.drawText(s, x, y, text)
    }

    private fun drawItem(c: Canvas, x: Float, y: Float, rad: Float, selected: Boolean, alpha: Int,
                         icon: Drawable?, letter: String, color: Int) {
        fill.color = if (selected) dark(color) else itemFill
        fill.alpha = alpha
        c.drawCircle(x, y, rad, fill)
        stroke.color = if (selected) color else Color.argb(255, Color.red(color) / 2 + 30, Color.green(color) / 2 + 30, Color.blue(color) / 2 + 30)
        stroke.alpha = alpha
        stroke.strokeWidth = dp(if (selected) 2.5f else 1.5f)
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
            text.color = if (selected) color else Color.parseColor("#D8D8D8")
            text.alpha = alpha
            text.textSize = rad * 0.8f
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textAlign = Paint.Align.CENTER
            c.drawText(letter, x, y + rad * 0.28f, text)
        }
    }
}
