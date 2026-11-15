package fr.perso.minilauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Personnalisation : groupes, rangement des applis, favoris, sons et vibrations. */
class SettingsActivity : Activity() {

    private val app get() = application as LauncherApp
    private val store get() = app.store
    private val repo get() = app.repo

    private val REQ_EXPORT = 41
    private val REQ_IMPORT = 42

    private lateinit var content: LinearLayout
    private var preview: android.view.View? = null

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.BLACK)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20f), dp(48f), dp(20f), dp(32f))
        scroll.addView(content)
        setContentView(scroll)
        build()
    }

    private fun title(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.setTextColor(Color.parseColor("#7CC4FF"))
        t.setPadding(0, dp(24f), 0, dp(8f))
        return t
    }

    private fun build() {
        content.removeAllViews()

        val h = TextView(this)
        h.text = "Personnaliser"
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        h.setTextColor(Color.parseColor("#F2F2F2"))
        content.addView(h)

        // --- changer d'appli d'accueil ---
        content.addView(title("APPLI D'ACCUEIL"))
        content.addView(smallButton("Changer d'appli d'accueil (revenir à l'ancien launcher)") {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })

        // --- réglages ---
        content.addView(title("SONS ET VIBRATIONS"))
        content.addView(toggle("Sons du menu radial", store.soundOn) { store.soundOn = it })
        content.addView(toggle("Vibrations légères", store.hapticOn) { store.hapticOn = it })

        // --- sauvegarde ---
        content.addView(title("SAUVEGARDE DES RÉGLAGES (survit à la désinstallation)"))
        content.addView(smallButton("Exporter mes réglages dans un fichier") {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "minilauncher-reglages.json")
            startActivityForResult(i, REQ_EXPORT)
        })
        content.addView(smallButton("Importer mes réglages depuis un fichier") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, REQ_IMPORT)
        })

        // --- portée du pouce ---
        content.addView(title("PORTÉE DU POUCE (adapte le menu à la taille de votre main)"))
        val prev = ReachPreview(this)
        preview = prev
        content.addView(prev, LinearLayout.LayoutParams(-1, dp(250f)))
        content.addView(slider("Bouton : distance au bord droit", 20, 260, store.menuMarginRight) { store.menuMarginRight = it; prev.invalidate() })
        content.addView(slider("Bouton : distance au bas de l'écran", 20, 400, store.menuMarginBottom) { store.menuMarginBottom = it; prev.invalidate() })
        content.addView(slider("Rayon des groupes (écart avec le pouce)", 0, 140, store.menuGap) { store.menuGap = it; prev.invalidate() })
        content.addView(slider("Rayon des applis (écart avec les groupes)", 20, 140, store.appsGap) { store.appsGap = it; prev.invalidate() })

        // --- fond d'écran ---
        content.addView(title("FOND D'ÉCRAN (monochrome, sobre)"))
        val sw = LinearLayout(this)
        sw.orientation = LinearLayout.HORIZONTAL
        for (i in WallpaperView.STYLE_NAMES.indices) {
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER_HORIZONTAL
            cell.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            cell.setBackgroundColor(if (store.wallpaperStyle == i) Color.parseColor("#7CC4FF") else Color.TRANSPARENT)
            val v = WallpaperView(this)
            v.set(i, store.wallpaperTint)
            cell.addView(v, LinearLayout.LayoutParams(dp(62f), dp(108f)))
            val n = TextView(this)
            n.text = WallpaperView.STYLE_NAMES[i]
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            n.setTextColor(if (store.wallpaperStyle == i) Color.BLACK else Color.parseColor("#A0A0A0"))
            cell.addView(n)
            cell.setOnClickListener { store.wallpaperStyle = i; build() }
            sw.addView(cell, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(6f) })
        }
        val hs = android.widget.HorizontalScrollView(this)
        hs.addView(sw)
        content.addView(hs)
        val tints = LinearLayout(this)
        tints.orientation = LinearLayout.HORIZONTAL
        tints.setPadding(0, dp(8f), 0, 0)
        for (i in WallpaperView.TINTS.indices) {
            val b = smallButton(WallpaperView.TINT_NAMES[i]) { store.wallpaperTint = i; build() }
            if (store.wallpaperTint == i) {
                b.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(WallpaperView.TINTS[i].second))
                b.setTextColor(Color.BLACK)
            }
            tints.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        content.addView(tints)

        // --- widgets ---
        content.addView(title("WIDGETS SOUS L'HEURE"))
        content.addView(toggle("Espace de stockage restant", store.widgetStorage) { store.widgetStorage = it })
        content.addView(toggle("Niveau de batterie", store.widgetBattery) { store.widgetBattery = it })
        content.addView(toggle("Notifications en attente", store.widgetNotifs) { store.widgetNotifs = it })
        content.addView(smallButton("Autoriser l'accès aux notifications (pour le compteur)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        // --- groupes ---
        content.addView(title("GROUPES DU MENU RADIAL (${store.groups().size} sur ${GroupStore.MAX_GROUPS})"))
        val hint = TextView(this)
        hint.text = "Touchez la pastille pour changer la couleur. ▲ ▼ pour réorganiser (le premier est en haut du menu)."
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        hint.setTextColor(Color.parseColor("#A0A0A0"))
        content.addView(hint)
        val gs = store.groups()
        for ((i, g) in gs.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(4f), 0, dp(4f))

            val dot = View(this)
            val shape = android.graphics.drawable.GradientDrawable()
            shape.shape = android.graphics.drawable.GradientDrawable.OVAL
            shape.setColor(store.colorOf(g))
            dot.background = shape
            dot.setOnClickListener { store.cycleColor(g); build() }
            row.addView(dot, LinearLayout.LayoutParams(dp(32f), dp(32f)).also { it.rightMargin = dp(12f) })

            val name = TextView(this)
            name.text = g
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            name.setTextColor(Color.parseColor("#F2F2F2"))
            name.setOnClickListener { askName("Renommer « $g »", g) { n -> store.renameGroup(g, n) } }
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))

            val up = smallButton("▲") { store.moveGroup(g, -1); build() }
            up.isEnabled = i > 0
            val down = smallButton("▼") { store.moveGroup(g, 1); build() }
            down.isEnabled = i < gs.size - 1
            row.addView(up, LinearLayout.LayoutParams(dp(48f), dp(44f)))
            row.addView(down, LinearLayout.LayoutParams(dp(48f), dp(44f)))
            row.addView(smallButton("✎") { askName("Renommer « $g »", g) { n -> store.renameGroup(g, n) } },
                LinearLayout.LayoutParams(dp(48f), dp(44f)))
            val del = smallButton("✕") { confirmDelete(g) }
            del.isEnabled = gs.size > 1
            row.addView(del, LinearLayout.LayoutParams(dp(48f), dp(44f)))
            content.addView(row)
        }
        val add = smallButton("+ Nouveau groupe") {
            if (store.groups().size >= GroupStore.MAX_GROUPS) {
                android.widget.Toast.makeText(this, "${GroupStore.MAX_GROUPS} groupes maximum", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                askName("Nom du groupe", "") { n -> store.addGroup(n) }
            }
        }
        content.addView(add)
        content.addView(smallButton("Rétablir le rangement par défaut") {
            store.resetAll()
            build()
        })
        content.addView(smallButton("Revoir la démonstration du geste") {
            store.replayDemo = true
            finish()
        })

        // --- applis ---
        content.addView(title("APPLIS : touchez le groupe pour le changer, l'étoile pour l'épingler à l'accueil"))
        val favs = store.favorites().toSet()
        for (a in repo.apps) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val name = TextView(this)
            name.text = a.label
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            name.setTextColor(Color.parseColor("#F2F2F2"))
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            val gb = smallButton(store.groupOf(a)) { chooseGroup(a) }
            gb.backgroundTintList = android.content.res.ColorStateList.valueOf(store.colorOf(store.groupOf(a)))
            gb.setTextColor(Color.WHITE)
            row.addView(gb)
            row.addView(smallButton(if (favs.contains(a.key)) "★" else "☆") {
                store.toggleFavorite(a.key)
                build()
            })
            content.addView(row)
        }
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, onSet: (Int) -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.setTextColor(Color.parseColor("#F2F2F2"))
        t.text = "$label : $value"
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = value - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                t.text = "$label : ${p + min}"
                if (fromUser) onSet(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { onSet((s?.progress ?: 0) + min) }
        })
        box.addView(t)
        box.addView(sb)
        return box
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            if (requestCode == REQ_EXPORT) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.exportJson().toByteArray()) }
                android.widget.Toast.makeText(this, "Réglages exportés", android.widget.Toast.LENGTH_LONG).show()
            } else if (requestCode == REQ_IMPORT) {
                val txt = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
                val ok = store.importJson(txt)
                android.widget.Toast.makeText(this, if (ok) "Réglages restaurés" else "Fichier non valide", android.widget.Toast.LENGTH_LONG).show()
                build()
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Échec : ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val s = Switch(this)
        s.text = label
        s.setTextColor(Color.parseColor("#F2F2F2"))
        s.isChecked = value
        s.setOnCheckedChangeListener { _, v -> onChange(v) }
        s.setPadding(0, dp(8f), 0, dp(8f))
        return s
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun askName(titleText: String, initial: String, apply: (String) -> Boolean) {
        val input = EditText(this)
        input.setText(initial)
        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                apply(input.text.toString())
                build()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmDelete(g: String) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer « $g » ?")
            .setMessage("Ses applis seront rangées dans le premier groupe restant.")
            .setPositiveButton("Supprimer") { _, _ -> store.deleteGroup(g); build() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun chooseGroup(a: AppEntry) {
        val groups = store.groups()
        val current = store.groupOf(a)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        val dialog = AlertDialog.Builder(this).setTitle(a.label).setView(box).setNegativeButton("Annuler", null).create()
        for (g in groups) {
            val b = Button(this)
            b.text = (if (g == current) "✓  " else "") + g
            b.isAllCaps = false
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            b.setTextColor(Color.WHITE)
            b.backgroundTintList = android.content.res.ColorStateList.valueOf(store.colorOf(g))
            b.setOnClickListener {
                store.setGroup(a, g)
                dialog.dismiss()
                build()
            }
            val lp = LinearLayout.LayoutParams(-1, dp(52f))
            lp.topMargin = dp(6f)
            box.addView(b, lp)
        }
        dialog.show()
    }

    /** Aperçu de l'écran : montre la zone de chaque rayon pour régler le menu à la portée du pouce. */
    private inner class ReachPreview(ctx: android.content.Context) : View(ctx) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()
        override fun onDraw(c: android.graphics.Canvas) {
            val dm = resources.displayMetrics
            val sw = dm.widthPixels / dm.density
            val sh = dm.heightPixels / dm.density
            val scale = minOf(width / sw, (height - dp(8f)) / sh)
            val w = sw * scale
            val h = sh * scale
            val left = width - w - dp(4f)
            val top = dp(4f)
            val ox = left + w
            val oy = top + h
            p.style = android.graphics.Paint.Style.FILL
            p.color = Color.parseColor("#0C0C0C")
            c.drawRect(left, top, ox, oy, p)
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = dp(1.5f)
            p.color = Color.parseColor("#4A4A4A")
            c.drawRect(left, top, ox, oy, p)

            val mr = store.menuMarginRight.toFloat()
            val mb = store.menuMarginBottom.toFloat()
            val thumbR = Math.hypot(mr.toDouble(), mb.toDouble()).toFloat()
            val gIn = maxOf(80f, thumbR + store.menuGap)
            val gOut = gIn + 100f
            val rApps = minOf(gIn + store.appsGap, sh - 110f)

            // bande des groupes
            val groups = store.groups()
            val n = groups.size + 1
            c.save()
            c.clipRect(left, top, ox, oy)
            p.style = android.graphics.Paint.Style.STROKE
            p.strokeWidth = 100f * scale
            val mid = (gIn + 50f) * scale
            r.set(ox - mid, oy - mid, ox + mid, oy + mid)
            for (i in 0 until n) {
                p.color = if (i == groups.size) Color.parseColor("#9E9E9E") else store.colorOf(groups[i])
                p.alpha = 150
                c.drawArc(r, 180f + 90f / n * i, 90f / n - 1.5f, false, p)
            }
            // arc des applis
            p.color = Color.WHITE
            p.alpha = 160
            p.strokeWidth = dp(1.2f)
            val ra = rApps * scale
            r.set(ox - ra, oy - ra, ox + ra, oy + ra)
            c.drawArc(r, 180f, 90f, false, p)
            p.style = android.graphics.Paint.Style.FILL
            for (k in 0 until 5) {
                val a = Math.toRadians((100.0 + (170.0 - 100.0) * k / 4))
                c.drawCircle(ox + ra * Math.cos(a).toFloat(), oy - ra * Math.sin(a).toFloat(), dp(4f), p)
            }
            c.restore()
            // pouce
            p.color = Color.parseColor("#7CC4FF")
            p.alpha = 255
            c.drawCircle(ox - mr * scale, oy - mb * scale, dp(8f), p)
        }
    }
}
