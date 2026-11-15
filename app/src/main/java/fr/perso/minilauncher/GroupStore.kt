package fr.perso.minilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Groupes, rangement personnalisé, favoris et dernières applis lancées (persistés). */
class GroupStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private var overridesCache: MutableMap<String, String>? = null

    companion object {
        const val MAX_GROUPS = 5
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
        saveGroups(g + n)
        return true
    }

    fun renameGroup(old: String, new: String): Boolean {
        val n = new.trim()
        val g = groups()
        if (n.isEmpty() || (n != old && g.contains(n))) return false
        saveGroups(g.map { if (it == old) n else it })
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

    fun resetAll() {
        sp.edit().remove("groups").remove("overrides").apply()
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

    private fun readList(s: String): List<String> {
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getString(it) }
    }
}
