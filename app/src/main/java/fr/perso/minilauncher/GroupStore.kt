package fr.perso.minilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Groupes, rangement personnalisé, favoris et dernières applis lancées (persistés). */
class GroupStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private var overridesCache: MutableMap<String, String>? = null

    companion object {
        const val MAX_GROUPS = 6

        /** Couleurs proposées pour les groupes (vives, lisibles sur fond noir). */
        val PALETTE: List<Int> = listOf(
            0xFF29B6F6.toInt(), // bleu
            0xFFFF7043.toInt(), // orange
            0xFF66BB6A.toInt(), // vert
            0xFFAB47BC.toInt(), // violet
            0xFFFFCA28.toInt(), // jaune
            0xFFEC407A.toInt(), // rose
            0xFF26C6DA.toInt(), // turquoise
            0xFFD4E157.toInt()  // citron
        )
    }

    // ---- groupes ----
    fun groups(): List<String> {
        val s = sp.getString("groups", null) ?: return Categorizer.defaultGroups
        return readList(s)
    }

    private fun saveGroups(l: List<String>) {
        sp.edit().putString("groups", JSONArray(l).toString()).apply()
    }

    fun addGroup(name: String): Boolean {
        val n = name.trim()
        val g = groups()
        if (n.isEmpty() || g.size >= MAX_GROUPS || g.contains(n)) return false
        val used = g.map { colorIndex(it) }.toSet()
        val free = PALETTE.indices.firstOrNull { it !in used } ?: (g.size % PALETTE.size)
        saveGroups(g + n)
        val cm = colorMap()
        cm[n] = free
        saveColors(cm)
        return true
    }

    fun renameGroup(old: String, new: String): Boolean {
        val n = new.trim()
        val g = groups()
        if (n.isEmpty() || (n != old && g.contains(n))) return false
        val idx = colorIndex(old)
        saveGroups(g.map { if (it == old) n else it })
        val cm = colorMap()
        cm.remove(old)
        cm[n] = idx
        saveColors(cm)
        val ov = overrides()
        for (k in ov.keys.toList()) if (ov[k] == old) ov[k] = n
        saveOverrides(ov)
        return true
    }

    fun deleteGroup(name: String) {
        val g = groups()
        if (g.size <= 1) return
        saveGroups(g.filter { it != name })
        val ov = overrides()
        for (k in ov.keys.toList()) if (ov[k] == name) ov.remove(k)
        saveOverrides(ov)
    }

    /** Déplace un groupe vers le haut (-1) ou le bas (+1) dans la liste. */
    fun moveGroup(name: String, delta: Int) {
        val g = groups().toMutableList()
        val i = g.indexOf(name)
        val j = i + delta
        if (i < 0 || j < 0 || j >= g.size) return
        g.removeAt(i)
        g.add(j, name)
        saveGroups(g)
    }

    // ---- couleurs ----
    private fun colorMap(): MutableMap<String, Int> {
        val map = HashMap<String, Int>()
        val s = sp.getString("colors", null) ?: return map
        val o = JSONObject(s)
        for (k in o.keys()) map[k] = o.getInt(k)
        return map
    }

    private fun saveColors(m: Map<String, Int>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        sp.edit().putString("colors", o.toString()).apply()
    }

    /** Index de couleur du groupe : choisi par l'utilisateur, sinon selon sa position. */
    fun colorIndex(name: String): Int {
        val saved = colorMap()[name]
        if (saved != null) return Math.floorMod(saved, PALETTE.size)
        return Math.floorMod(groups().indexOf(name).coerceAtLeast(0), PALETTE.size)
    }

    fun colorOf(name: String): Int = PALETTE[colorIndex(name)]

    fun cycleColor(name: String) {
        val m = colorMap()
        m[name] = (colorIndex(name) + 1) % PALETTE.size
        saveColors(m)
    }

    fun resetAll() {
        sp.edit().remove("groups").remove("overrides").remove("colors").apply()
        overridesCache = null
    }

    // ---- affectation des applis ----
    private fun overrides(): MutableMap<String, String> {
        overridesCache?.let { return it }
        val map = HashMap<String, String>()
        val s = sp.getString("overrides", null)
        if (s != null) {
            val o = JSONObject(s)
            for (k in o.keys()) map[k] = o.getString(k)
        }
        overridesCache = map
        return map
    }

    private fun saveOverrides(m: Map<String, String>) {
        overridesCache = HashMap(m)
        sp.edit().putString("overrides", JSONObject(m).toString()).apply()
    }

    fun groupOf(app: AppEntry): String {
        val gs = groups()
        val ov = overrides()[app.key]
        return when {
            ov != null && gs.contains(ov) -> ov
            gs.contains(app.defaultGroup) -> app.defaultGroup
            else -> gs.firstOrNull() ?: app.defaultGroup
        }
    }

    fun setGroup(app: AppEntry, group: String) {
        val ov = overrides()
        ov[app.key] = group
        saveOverrides(ov)
    }

    fun appsIn(group: String, all: List<AppEntry>): List<AppEntry> =
        all.filter { groupOf(it) == group }.sortedBy { it.label.lowercase() }

    // ---- favoris ----
    fun hasFavorites(): Boolean = sp.contains("favorites")

    fun favorites(): List<String> = sp.getString("favorites", null)?.let { readList(it) } ?: emptyList()

    fun toggleFavorite(key: String) {
        val f = favorites().toMutableList()
        if (!f.remove(key)) f.add(key)
        sp.edit().putString("favorites", JSONArray(f).toString()).apply()
    }

    // ---- dernières applis ----
    fun pushRecent(key: String) {
        val r = recents().toMutableList()
        r.remove(key)
        r.add(0, key)
        sp.edit().putString("recents", JSONArray(r.take(10)).toString()).apply()
    }

    fun recents(): List<String> = sp.getString("recents", null)?.let { readList(it) } ?: emptyList()

    // ---- réglages ----
    var soundOn: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) { sp.edit().putBoolean("sound", v).apply() }

    var hapticOn: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) { sp.edit().putBoolean("haptic", v).apply() }

    /** Position du centre du menu radial : distance (en dp) au bord droit et au bas de l'écran. */
    var menuMarginRight: Int
        get() = sp.getInt("menu_right", 154)
        set(v) { sp.edit().putInt("menu_right", v).apply() }

    var menuMarginBottom: Int
        get() = sp.getInt("menu_bottom", 200)
        set(v) { sp.edit().putInt("menu_bottom", v).apply() }

    /** Demande de rejouer l'animation de démonstration à la prochaine arrivée sur l'accueil. */
    var replayDemo: Boolean
        get() = sp.getBoolean("replay_demo", false)
        set(v) { sp.edit().putBoolean("replay_demo", v).apply() }

    private fun readList(s: String): List<String> {
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getString(it) }
    }
}
