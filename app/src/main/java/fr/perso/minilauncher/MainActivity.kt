package fr.perso.minilauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView

class MainActivity : Activity(), RadialMenuView.Host {

    companion object {
        /** La démonstration du geste est jouée à chaque démarrage de l'appli. */
        private var demoShown = false
    }

    private val app get() = application as LauncherApp
    private val store get() = app.store
    private val repo get() = app.repo

    private lateinit var sounds: Sounds
    private lateinit var radial: RadialMenuView
    private lateinit var favorites: LinearLayout
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchList: ListView
    private var searchResults: List<AppEntry> = emptyList()
    private lateinit var gestures: GestureDetector

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Thread { repo.reload() }.start()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Float) = dp(v).toInt()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        sounds = Sounds(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // --- heure, date, favoris ---
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dpi(28f), dpi(72f), dpi(28f), 0)

        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
        clock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        clock.setTextColor(Color.parseColor("#F2F2F2"))
        clock.includeFontPadding = false
        clock.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        val date = TextClock(this)
        date.format24Hour = "EEEE d MMMM"
        date.format12Hour = "EEEE d MMMM"
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        date.setTextColor(Color.parseColor("#A0A0A0"))
        date.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        favorites = LinearLayout(this)
        favorites.orientation = LinearLayout.VERTICAL
        val favParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        favParams.topMargin = dpi(60f)

        column.addView(clock)
        column.addView(date)
        column.addView(favorites, favParams)
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // --- menu radial (au-dessus de l'accueil) ---
        radial = RadialMenuView(this)
        radial.host = this
        radial.sounds = sounds
        root.addView(radial, FrameLayout.LayoutParams(-1, -1))

        // --- recherche (balayage vers le haut) ---
        searchPanel = LinearLayout(this)
        searchPanel.orientation = LinearLayout.VERTICAL
        searchPanel.setBackgroundColor(Color.parseColor("#F2000000"))
        searchPanel.setPadding(dpi(24f), dpi(64f), dpi(24f), dpi(24f))
        searchPanel.visibility = View.GONE
        searchPanel.isClickable = true

        searchInput = EditText(this)
        searchInput.hint = "Rechercher"
        searchInput.setHintTextColor(Color.parseColor("#8C8C8C"))
        searchInput.setTextColor(Color.parseColor("#F2F2F2"))
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        searchInput.setSingleLine(true)
        searchInput.imeOptions = EditorInfo.IME_ACTION_GO
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshSearch(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        searchInput.setOnEditorActionListener { _, _, _ ->
            searchResults.firstOrNull()?.let { closeSearch(); launch(it) }
            true
        }
        searchList = ListView(this)
        searchList.divider = null
        searchList.setOnItemClickListener { _, _, pos, _ ->
            searchResults.getOrNull(pos)?.let { closeSearch(); launch(it) }
        }
        searchPanel.addView(searchInput)
        searchPanel.addView(searchList, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(searchPanel, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 != null && !radial.gestureWasRadial && vy < -1200f &&
                    e1.y - e2.y > dp(80f) && searchPanel.visibility != View.VISIBLE
                ) {
                    openSearch()
                    return true
                }
                return false
            }
        })

        repo.onChanged = { refreshFavorites() }

        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_REPLACED)
        f.addDataScheme("package")
        registerReceiver(packageReceiver, f)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        sounds.enabled = store.soundOn
        radial.hapticEnabled = store.hapticOn
        radial.refresh()
        if (!demoShown || store.replayDemo) {
            demoShown = true
            store.replayDemo = false
            radial.postDelayed({ radial.playDemo() }, 700)
        }
        refreshFavorites()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeSearch()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (searchPanel.visibility == View.VISIBLE) closeSearch()
        // sur l'écran d'accueil, « retour » ne fait rien
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        repo.onChanged = null
        sounds.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- favoris

    private fun refreshFavorites() {
        favorites.removeAllViews()
        val all = repo.apps
        // aucun favori par défaut : l'accueil reste vide tant que l'utilisateur n'a rien épinglé
        val list: List<AppEntry> = store.favorites().mapNotNull { k -> all.firstOrNull { it.key == k } }
        for (a in list) {
            val tv = TextView(this)
            tv.text = a.label
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            tv.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            tv.setTextColor(Color.parseColor("#F2F2F2"))
            tv.setPadding(0, dpi(8f), 0, dpi(8f))
            tv.setOnClickListener { launch(a) }
            favorites.addView(tv)
        }
    }

    // ---------------------------------------------------------------- recherche

    private fun openSearch() {
        searchInput.setText("")
        refreshSearch("")
        searchPanel.visibility = View.VISIBLE
        searchInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        if (searchPanel.visibility != View.VISIBLE) return
        searchPanel.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun refreshSearch(q: String) {
        val query = q.trim().lowercase()
        searchResults = if (query.isEmpty()) emptyList()
        else repo.apps.filter { it.label.lowercase().contains(query) }.take(30)
        searchList.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, searchResults.map { it.label }
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.parseColor("#F2F2F2"))
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                v.gravity = Gravity.CENTER_VERTICAL
                return v
            }
        }
    }

    // ---------------------------------------------------------------- RadialMenuView.Host

    override fun groups(): List<String> = store.groups()

    override fun appsIn(group: String): List<AppEntry> = store.appsIn(group, repo.apps)

    override fun menuMarginRight(): Int = store.menuMarginRight
    override fun menuMarginBottom(): Int = store.menuMarginBottom

    override fun groupColor(group: String): Int = store.colorOf(group)

    override fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun launch(app: AppEntry) {
        store.pushRecent(app.key)
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
            startActivity(app.launchIntent, opts.toBundle())
        } catch (e: Exception) {
            // appli désinstallée entre-temps : on ignore
        }
    }

    override fun launchLast() {
        val key = store.recents().firstOrNull() ?: return
        repo.apps.firstOrNull { it.key == key }?.let { launch(it) }
    }
}
