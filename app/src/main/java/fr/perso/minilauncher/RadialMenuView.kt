package fr.perso.minilauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.animation.OvershootInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Menu radial en quart de cercle, centré sur le coin bas-droit de l'écran et collé aux bords droit et bas.
 * - appui sur le bouton : les groupes jaillissent en éventail (animation) ;
 * - glisser : choisit le groupe (dernier segment = réglages) ;
 * - pousser vers le bord : ouvre le groupe, les applis s'affichent sur le grand arc extérieur ;
 * - revenir vers l'intérieur : referme le groupe ;
 * - relâcher sur une appli : la lance.
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
        fun menuGap(): Int
        fun appsGap(): Int
    }

    private enum class Mode { IDLE, GROUPS, APPS }

    var host: Host? = null
    var sounds: Sounds? = null
    var hapticEnabled: Boolean = true

    /** Vrai si le dernier geste a commencé sur le bouton (sert à ignorer le swipe de recherche). */
    var gestureWasRadial: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity

    private val accent = Color.parseColor("#7CC4FF")
    private val itemFill = Color.parseColor("#141414")
    private val grey = Color.parseColor("#A0A0A0")
    private val settingsColor = Color.parseColor("#9E9E9E")

    // ------------------------------------------------------------------ géométrie
    // origine du quart de cercle = coin bas-droit de l'écran
    private var ox = 0f
    private var oy = 0f
    // bouton (là où le pouce se pose)
    private var bx = 0f
    private var by = 0f
    // rayons (depuis le coin)
    private var gIn = 0f
    private var gOut = 0f
    private var rApps = 0f

    private val START = 90f   // le long du bord droit
    private val END = 180f    // le long du bord bas
    private var SLOT = 13f
    private var APPS_CENTER = 135f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeCenter()
    }

    private fun placeCenter() {
        if (width == 0) return
        ox = width.toFloat()
        oy = height.toFloat()
        val mr = host?.menuMarginRight() ?: 154
        val mb = host?.menuMarginBottom() ?: 200
        bx = width - dp(mr.toFloat())
        by = height - dp(mb.toFloat())
        // les segments de groupes commencent au-delà du pouce : il reste dans la zone libre du centre,
        // loin des traits de séparation ; le menu s'agrandit en conséquence
        val thumbR = hypot(ox - bx, oy - by)
        gIn = max(dp(80f), thumbR + dp((host?.menuGap() ?: 26).toFloat()))
        gOut = gIn + dp(100f)
        rApps = (gIn + dp((host?.appsGap() ?: 52).toFloat())).coerceAtMost(height - dp(110f)) // les applis prennent la place des groupes
        // l'arc des applis doit rester dans l'écran : on limite l'angle côté gauche
        val ratio = ((width - dp(36f)) / rApps).coerceIn(0.2f, 1f)
        val aMax = 180f - Math.toDegrees(Math.acos(ratio.toDouble())).toFloat()
        val aMin = 100f
        val hi = min(aMax, 172f)
        APPS_CENTER = (aMin + hi) / 2f
        SLOT = ((hi - aMin) / 5f).coerceIn(9f, 15f)
    }

    /** À appeler quand les groupes, leurs couleurs ou la position du bouton ont pu changer. */
    fun refresh() {
        if (!touching && !demo) {
            placeCenter()
            groupList = host?.groups() ?: emptyList()
        }
        invalidate()
    }

    private fun px(a: Float, r: Float): Float = ox + r * cos(Math.toRadians(a.toDouble())).toFloat()
    private fun py(a: Float, r: Float): Float = oy - r * sin(Math.toRadians(a.toDouble())).toFloat()

    private fun thetaOf(x: Float, y: Float): Float {
        var th = Math.toDegrees(atan2((oy - y).toDouble(), (x - ox).toDouble())).toFloat()
        if (th < 0f) th += 360f
        return th.coerceIn(START, END)
    }

    private fun total() = groupList.size + 1
    private fun step() = (END - START) / total()
    private fun itemAngle(i: Int) = START + step() * (i + 0.5f)
    private fun settingsIdx() = groupList.size
    private fun segmentAt(th: Float): Int = ((th - START) / step()).toInt().coerceIn(0, total() - 1)
    private fun slotAngle(k: Int, m: Int): Float = APPS_CENTER - SLOT * (m - 1) / 2f + SLOT * k

    private fun colorOfIdx(i: Int): Int =
        if (i == settingsIdx()) settingsColor else host?.groupColor(groupList[i]) ?: accent

    private fun currentColor(): Int = when {
        mode == Mode.APPS && openGroupIdx in groupList.indices -> colorOfIdx(openGroupIdx)
        mode == Mode.GROUPS && hoverGroup in 0 until total() -> colorOfIdx(hoverGroup)
        else -> accent
    }

    // ------------------------------------------------------------------ état

    private var mode = Mode.IDLE
    private var touching = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var thumbX = 0f
    private var thumbY = 0f

    private var groupList: List<String> = emptyList()
    private var hoverGroup = -1
    private var openGroupIdx = -1

    private var apps: List<AppEntry> = emptyList()
    private var offset = 0
    private var hoverSlot = 0
    private var armed = false
    private var rRef = 0f // rayon de référence : on ouvre/ferme selon le mouvement vers l'extérieur/l'intérieur
    private var edgeDir = 0
    private var edgeOver = 0f

    // animations d'apparition
    private val overshoot = OvershootInterpolator(2.4f)
    private var openT = 1f
    private var openAnim: ValueAnimator? = null
    private var appsT = 1f
    private var appsAnim: ValueAnimator? = null
    private var retractT = 0f // 0 = groupes déployés, 1 = groupes rétractés (les applis ont pris leur place)
    private var retractAnim: ValueAnimator? = null

    // démonstration
    private var demo = false
    private var demoAnim: ValueAnimator? = null
    private var demoCaption = ""
    private var demoPulse = 0f
    private class Kf(val t: Float, val x: Float, val y: Float, val caption: String)
    private var kfs: List<Kf> = emptyList()
    val isDemoPlaying: Boolean get() = demo

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
        val t = edgeOver.coerceIn(0f, 12f) / 12f
        return (260f - t * 170f).toLong()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private val outerRect = RectF()
    private val innerRect = RectF()
    private val sectorPath = Path()

    // ------------------------------------------------------------------ gestes

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - bx, e.y - by) > dp(48f)) {
                    gestureWasRadial = false
                    return false
                }
                stopDemo()
                gestureWasRadial = true
                parent?.requestDisallowInterceptTouchEvent(true)
                press(e.x, e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (touching) update(e.x, e.y)
            MotionEvent.ACTION_UP -> if (touching) release()
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return touching
    }

    /** Appui sur le bouton : le menu des groupes jaillit. */
    private fun press(x: Float, y: Float) {
        placeCenter()
        touching = true
        downX = x; downY = y
        moved = false
        groupList = host?.groups() ?: emptyList()
        hoverGroup = -1
        rRef = hypot(x - ox, y - oy)
        mode = Mode.GROUPS
        startOpenAnim()
        snd?.open()
        haptic()
        update(x, y)
    }

    private fun update(x: Float, y: Float) {
        thumbX = x
        thumbY = y
        if (!moved && hypot(x - downX, y - downY) > dp(10f)) moved = true
        val r = hypot(x - ox, y - oy)
        val th = thetaOf(x, y)

        if (mode == Mode.GROUPS) {
            if (moved && groupList.isNotEmpty()) {
                val h = segmentAt(th)
                if (h != hoverGroup) {
                    hoverGroup = h
                    rRef = r
                    snd?.tick()
                    haptic()
                }
            }
            rRef = min(rRef, r)
            // glissement vers l'extérieur, où que soit le pouce sur l'écran : le groupe s'ouvre
            if (moved && r - rRef > dp(38f) && hoverGroup in groupList.indices) openGroup(hoverGroup, th)
        } else if (mode == Mode.APPS) {
            rRef = max(rRef, r)
            // glissement vers l'intérieur : le groupe se referme
            if (rRef - r > dp(42f)) closeGroup() else updateApps(th)
        }
        invalidate()
    }

    private fun openGroup(idx: Int, th: Float) {
        val list = host?.appsIn(groupList[idx]) ?: emptyList()
        if (list.isEmpty()) return
        apps = list
        armed = false
        openGroupIdx = idx
        offset = 0
        hoverSlot = 0
        edgeDir = 0
        mode = Mode.APPS
        rRef = hypot(thumbX - ox, thumbY - oy)
        startAppsAnim()
        snd?.open()
        haptic()
        updateApps(th)
    }

    private fun closeGroup() {
        stopEdge()
        animateRetract(0f)
        mode = Mode.GROUPS
        hoverGroup = openGroupIdx
        rRef = hypot(thumbX - ox, thumbY - oy)
        snd?.close()
        haptic()
    }

    private fun updateApps(th: Float) {
        val n = apps.size
        if (n == 0) return
        val m = min(n, 5)
        val a0 = slotAngle(0, m)
        val aLast = slotAngle(m - 1, m)
        if (!armed && th >= a0 - 8f && th <= aLast + 8f) armed = true
        val slot = ((th - a0) / SLOT).roundToInt().coerceIn(0, m - 1)
        if (slot != hoverSlot) {
            hoverSlot = slot
            snd?.tick()
            haptic()
        }
        var dir = 0
        var over = 0f
        if (n > m && armed) {
            if (th < a0 - 6f) { dir = -1; over = a0 - th }
            else if (th > aLast + 6f) { dir = 1; over = th - aLast }
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
        val m = mode
        val app = if (m == Mode.APPS && armed && apps.isNotEmpty())
            apps[Math.floorMod(offset + hoverSlot, apps.size)] else null
        val onSettings = m == Mode.GROUPS && moved && hoverGroup == settingsIdx()
        reset()
        when {
            app != null -> host?.launch(app)
            onSettings -> host?.openSettings()
        }
    }

    private fun reset() {
        retractAnim?.cancel()
        retractT = 0f
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

    // ------------------------------------------------------------------ animations d'apparition

    private fun startOpenAnim() {
        openAnim?.cancel()
        openT = 0f
        openAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560
            interpolator = LinearInterpolator()
            addUpdateListener { openT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun animateRetract(to: Float) {
        retractAnim?.cancel()
        val from = retractT
        retractAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 300
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { retractT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startAppsAnim() {
        animateRetract(1f)
        appsAnim?.cancel()
        appsT = 0f
        appsAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = LinearInterpolator()
            addUpdateListener { appsT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Avancement (avec rebond) d'un élément i sur n, apparitions décalées en cascade. */
    private fun stagger(t: Float, i: Int, n: Int, spread: Float): Float {
        val delay = if (n <= 1) 0f else spread * i / (n - 1)
        val u = ((t - delay) / (1f - spread)).coerceIn(0f, 1f)
        return overshoot.getInterpolation(u)
    }

    // ------------------------------------------------------------------ démonstration

    /** Joue une animation qui montre comment le pouce doit agir. Un tapotement l'interrompt. */
    fun playDemo() {
        if (touching || demo || width == 0) return
        placeCenter()
        val h = host ?: return
        groupList = h.groups()
        if (groupList.isEmpty()) return
        val target = groupList.indices.firstOrNull { h.appsIn(groupList[it]).isNotEmpty() } ?: 0
        val rMid = hypot(ox - bx, oy - by) // le pouce reste à ce rayon pour choisir un groupe
        val thFirst = itemAngle(0)
        val thTarget = itemAngle(target)
        val thLast = itemAngle(groupList.size - 1)
        kfs = listOf(
            Kf(0f, bx, by, "Posez le pouce sur le bouton :\nles groupes apparaissent"),
            Kf(1300f, bx, by, "Glissez pour choisir un groupe"),
            Kf(2200f, px(thFirst, rMid), py(thFirst, rMid), "Glissez pour choisir un groupe"),
            Kf(3000f, px(thLast, rMid), py(thLast, rMid), "Glissez pour choisir un groupe"),
            Kf(3800f, px(thTarget, rMid), py(thTarget, rMid), "Glissez vers l'extérieur :\nle groupe s'ouvre"),
            Kf(4700f, px(thTarget, rApps), py(thTarget, rApps), "Glissez le long du grand arc\npour choisir l'appli"),
            Kf(6100f, px(APPS_CENTER + 8f, rApps), py(APPS_CENTER + 8f, rApps), "Relâchez pour lancer l'appli"),
            Kf(7200f, px(APPS_CENTER + 8f, rApps), py(APPS_CENTER + 8f, rApps), "Glissez vers l'intérieur\npour refermer le groupe"),
            Kf(8200f, px(APPS_CENTER, rMid), py(APPS_CENTER, rMid), "Le dernier segment ⚙\nouvre les réglages"),
            Kf(9400f, bx, by, "")
        )
        demo = true
        press(bx, by)
        demoAnim?.cancel()
        demoAnim = ValueAnimator.ofFloat(0f, kfs.last().t).apply {
            duration = kfs.last().t.toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { a -> demoStep(a.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
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
        val e = u * u * (3f - 2f * u)
        demoCaption = a.caption
        demoPulse = (t % 900f) / 900f
        update(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e)
    }

    fun stopDemo() {
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
            return
        }
        c.drawColor(Color.argb((165 * min(1f, openT * 2f)).toInt(), 0, 0, 0))

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
        } else {
            drawCenterText(c, "Glissez", "vers un groupe", grey)
        }
        drawHandle(c)
        if (demo) drawDemo(c)
    }

    private fun centerY(): Float = min(height * 0.30f, oy - rApps - dp(170f)).coerceAtLeast(dp(120f))

    /** Bouton au repos : un anneau découpé aux couleurs des groupes. */
    private fun drawRestHandle(c: Canvas) {
        val n = groupList.size
        if (n > 0) {
            val sweep = 360f / n
            stroke.strokeWidth = dp(4f)
            stroke.pathEffect = null
            stroke.strokeCap = Paint.Cap.BUTT
            arcRect.set(bx - dp(27f), by - dp(27f), bx + dp(27f), by + dp(27f))
            for (i in 0 until n) {
                stroke.color = host?.groupColor(groupList[i]) ?: accent
                stroke.alpha = 235
                c.drawArc(arcRect, -90f + sweep * i + 3f, sweep - 6f, false, stroke)
            }
        }
        fill.color = Color.parseColor("#1E1E1E"); fill.alpha = 255
        c.drawCircle(bx, by, dp(21f), fill)
        fill.color = accent; fill.alpha = 255
        c.drawCircle(bx, by, dp(5f), fill)
    }

    /** Secteur d'anneau centré sur le coin, entre deux angles et deux rayons. */
    private fun sector(a1: Float, a2: Float, rIn: Float, rOut: Float): Path {
        outerRect.set(ox - rOut, oy - rOut, ox + rOut, oy + rOut)
        innerRect.set(ox - rIn, oy - rIn, ox + rIn, oy + rIn)
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
            val p = stagger(openT, i, total, 0.45f)
            if (p <= 0.01f) continue
            val pc = min(1f, p)
            val color = colorOfIdx(i)
            val selected = if (mode == Mode.APPS) i == openGroupIdx else i == hoverGroup
            val grow = if (selected && mode == Mode.GROUPS && retractT < 0.05f) dp(16f) else 0f
            // jaillit depuis le coin en tournant légèrement
            val rot = (1f - pc) * 28f
            val a1 = START + st * i + 1.2f + rot
            val a2 = START + st * (i + 1) - 1.2f + rot
            val rOut = gIn + (gOut + grow - gIn) * p
            // quand un groupe est ouvert, les segments se rétractent en fine bande près du coin
            val thin = gIn - dp(4f)
            val rInD = gIn * pc + ((gIn - dp(14f)) - gIn * pc) * retractT
            val rOutD = rOut + (thin - rOut) * retractT
            val path = sector(a1, a2, rInD, rOutD)
            val alpha = when {
                mode == Mode.APPS -> if (selected) 220 else 55
                selected -> 255
                else -> 165
            }
            fill.color = color
            fill.alpha = (alpha * pc).toInt()
            c.drawPath(path, fill)
            stroke.color = color
            stroke.alpha = ((if (mode == Mode.APPS && !selected) 70 else 255) * pc).toInt()
            stroke.strokeWidth = dp(if (selected) 3f else 1.2f)
            stroke.pathEffect = null
            c.drawPath(path, stroke)

            if (pc < 0.6f || retractT > 0.25f) continue
            val mid = (a1 + a2) / 2f
            val rl = (gIn * pc + rOut) / 2f
            val lx = px(mid, rl)
            val ly = py(mid, rl)
            val txtAlpha = if (mode == Mode.APPS && !selected) 90 else 255
            if (i == settingsIdx()) {
                drawGear(c, lx, ly, dp(13f), Color.WHITE, txtAlpha)
            } else {
                text.reset(); text.isAntiAlias = true
                text.color = Color.WHITE
                text.alpha = txtAlpha
                text.textSize = sp(if (selected) 30f else 24f)
                text.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                text.textAlign = Paint.Align.CENTER
                c.drawText(groupList[i].take(1).uppercase(), lx, ly + sp(4f), text)
                // nom complet si la place le permet
                text.textSize = sp(12f)
                text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val arcLen = Math.toRadians(st.toDouble()).toFloat() * rl
                val name = groupList[i]
                if (text.measureText(name) < arcLen * 0.85f) c.drawText(name, lx, ly + sp(22f), text)
            }
        }
    }

    private fun drawGear(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        stroke.color = color
        stroke.alpha = alpha
        stroke.pathEffect = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = dp(2.5f)
        c.drawCircle(x, y, r * 0.55f, stroke)
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            c.drawLine(
                x + r * 0.75f * cos(a).toFloat(), y + r * 0.75f * sin(a).toFloat(),
                x + r * cos(a).toFloat(), y + r * sin(a).toFloat(), stroke
            )
        }
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun dark(color: Int): Int =
        Color.rgb((Color.red(color) * 0.28f).toInt(), (Color.green(color) * 0.28f).toInt(), (Color.blue(color) * 0.28f).toInt())

    private fun drawAppsRing(c: Canvas) {
        val color = currentColor()
        stroke.color = color
        stroke.alpha = 200
        stroke.strokeWidth = dp(1.5f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(6f)), 0f)
        arcRect.set(ox - rApps, oy - rApps, ox + rApps, oy + rApps)
        c.drawArc(arcRect, 180f, 90f, false, stroke)
        stroke.pathEffect = null

        val n = apps.size
        if (n == 0) return
        val m = min(n, 5)
        val from = itemAngle(openGroupIdx)
        for (k in 0 until m) {
            val p = stagger(appsT, k, m, 0.4f)
            val a = from + (slotAngle(k, m) - from) * min(1f, p)
            val r0 = gIn - dp(9f)
            val r = r0 + (rApps - r0) * p
            val app = apps[Math.floorMod(offset + k, n)]
            val selected = k == hoverSlot && armed
            drawItem(c, px(a, r), py(a, r), if (selected) dp(30f) else dp(24f), selected,
                (255 * min(1f, p)).toInt(), app.icon, app.label.take(1).uppercase(), color)
        }
        if (n > m && appsT >= 1f) { // applis voisines estompées : la liste continue
            val aPrev = slotAngle(0, m) - SLOT
            val aNext = slotAngle(m - 1, m) + SLOT
            val prev = apps[Math.floorMod(offset - 1, n)]
            val next = apps[Math.floorMod(offset + m, n)]
            drawItem(c, px(aPrev, rApps), py(aPrev, rApps), dp(20f), false, 80, prev.icon, prev.label.take(1).uppercase(), color)
            drawItem(c, px(aNext, rApps), py(aNext, rApps), dp(20f), false, 80, next.icon, next.label.take(1).uppercase(), color)
        }

        val sel = apps[Math.floorMod(offset + hoverSlot, n)]
        // grande icône + nom au centre de l'écran
        val ccx = width / 2f
        val ccy = centerY()
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
        c.drawText("${groupList.getOrNull(openGroupIdx) ?: ""} · $idx sur $n", ccx, ccy + dp(56f) + sp(64f), text)
    }

    private fun drawCenterText(c: Canvas, title: String, sub: String, color: Int) {
        val ccx = width / 2f
        val ccy = centerY()
        text.reset(); text.isAntiAlias = true
        text.color = color
        text.textSize = sp(38f)
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        c.drawText(title, ccx, ccy, text)
        text.color = grey
        text.textSize = sp(14f)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(sub, ccx, ccy + sp(26f), text)
    }

    /** Flèche vers le coin sud-est : revenir vers l'intérieur referme le groupe. */
    private fun drawBackArrow(c: Canvas) {
        val color = currentColor()
        stroke.color = color
        stroke.alpha = 220
        stroke.strokeWidth = dp(2.5f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.pathEffect = null
        val x0 = ox - dp(46f)
        val y0 = oy - dp(46f)
        val p = Path()
        p.moveTo(x0, y0); p.lineTo(x0 + dp(24f), y0 + dp(24f))
        p.moveTo(x0 + dp(24f), y0 + dp(10f)); p.lineTo(x0 + dp(24f), y0 + dp(24f)); p.lineTo(x0 + dp(10f), y0 + dp(24f))
        c.drawPath(p, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }

    /** Poignée sous le pouce. */
    private fun drawHandle(c: Canvas) {
        val color = currentColor()
        val pop = 1f + 0.25f * (1f - min(1f, openT * 2f))
        fill.color = Color.parseColor("#1E1E1E"); fill.alpha = 230
        c.drawCircle(thumbX, thumbY, dp(24f) * pop, fill)
        stroke.color = color
        stroke.alpha = 255
        stroke.strokeWidth = dp(2.5f)
        stroke.pathEffect = null
        c.drawCircle(thumbX, thumbY, dp(24f) * pop, stroke)
        fill.color = color; fill.alpha = 255
        c.drawCircle(thumbX, thumbY, dp(6f), fill)
    }

    /** Pouce fantôme + légende pendant la démonstration. */
    private fun drawDemo(c: Canvas) {
        fill.color = Color.WHITE
        fill.alpha = 70
        c.drawCircle(thumbX, thumbY, dp(32f) + dp(10f) * demoPulse, fill)
        fill.alpha = 140
        c.drawCircle(thumbX, thumbY, dp(20f), fill)

        val caption = if (demoCaption.isEmpty()) "Touchez l'écran pour passer" else demoCaption
        text.reset(); text.isAntiAlias = true
        text.color = Color.WHITE
        text.textSize = sp(19f)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textAlign = Paint.Align.CENTER
        val lines = caption.split("\n")
        val baseY = dp(90f)
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, text.measureText(l))
        val lh = sp(26f)
        val bg = RectF(width / 2f - maxW / 2f - dp(18f), baseY - sp(24f),
            width / 2f + maxW / 2f + dp(18f), baseY + lh * (lines.size - 1) + sp(14f))
        fill.color = Color.parseColor("#121212"); fill.alpha = 235
        c.drawRoundRect(bg, dp(14f), dp(14f), fill)
        for ((k, l) in lines.withIndex()) c.drawText(l, width / 2f, baseY + lh * k, text)
        text.color = grey
        text.textSize = sp(12f)
        c.drawText("Touchez l'écran pour passer", width / 2f, bg.bottom + sp(18f), text)
    }

    private fun drawItem(c: Canvas, x: Float, y: Float, rad: Float, selected: Boolean, alpha: Int,
                         icon: Drawable?, letter: String, color: Int) {
        fill.color = if (selected) dark(color) else itemFill
        fill.alpha = alpha
        c.drawCircle(x, y, rad, fill)
        stroke.color = if (selected) color else Color.rgb(Color.red(color) / 2 + 30, Color.green(color) / 2 + 30, Color.blue(color) / 2 + 30)
        stroke.alpha = alpha
        stroke.strokeWidth = dp(if (selected) 3f else 1.5f)
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
