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

    private lateinit var content: LinearLayout

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

        // --- position du menu ---
        content.addView(title("POSITION DU MENU RADIAL (pour que le pouce reste détendu)"))
        content.addView(slider("Distance au bord droit", 40, 260, store.menuMarginRight) { store.menuMarginRight = it })
        content.addView(slider("Distance au bas de l'écran", 40, 400, store.menuMarginBottom) { store.menuMarginBottom = it })

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
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { onSet((s?.progress ?: 0) + min) }
        })
        box.addView(t)
        box.addView(sb)
        return box
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
}
