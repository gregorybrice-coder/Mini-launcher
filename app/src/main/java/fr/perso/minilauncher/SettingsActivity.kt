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

        // --- groupes ---
        content.addView(title("GROUPES (${store.groups().size} sur ${GroupStore.MAX_GROUPS})"))
        for (g in store.groups()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val name = TextView(this)
            name.text = g
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            name.setTextColor(Color.parseColor("#F2F2F2"))
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(smallButton("Renommer") { askName("Renommer « $g »", g) { n -> store.renameGroup(g, n) } })
            row.addView(smallButton("Supprimer") {
                store.deleteGroup(g)
                build()
            })
            content.addView(row)
        }
        content.addView(smallButton("+ Nouveau groupe") {
            askName("Nom du groupe", "") { n -> store.addGroup(n) }
        })
        content.addView(smallButton("Rétablir le rangement par défaut") {
            store.resetAll()
            build()
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
            row.addView(smallButton(store.groupOf(a)) { chooseGroup(a) })
            row.addView(smallButton(if (favs.contains(a.key)) "★" else "☆") {
                store.toggleFavorite(a.key)
                build()
            })
            content.addView(row)
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

    private fun chooseGroup(a: AppEntry) {
        val groups = store.groups()
        AlertDialog.Builder(this)
            .setTitle(a.label)
            .setItems(groups.toTypedArray()) { _, which ->
                store.setGroup(a, groups[which])
                build()
            }
            .show()
    }
}
