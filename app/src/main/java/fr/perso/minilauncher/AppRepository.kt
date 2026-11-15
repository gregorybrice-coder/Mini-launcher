package fr.perso.minilauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Liste des applis installées, avec icônes et noms mis en cache en mémoire. */
class AppRepository(private val ctx: Context) {

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    /** Appelé sur le thread principal quand la liste a changé. */
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun reload() {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = ArrayList<AppEntry>()
        for (ri in pm.queryIntentActivities(query, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == ctx.packageName) continue
            val label = ri.loadLabel(pm).toString()
            val entry = AppEntry(
                label,
                ai.packageName,
                ComponentName(ai.packageName, ai.name),
                Categorizer.defaultGroupFor(ai.applicationInfo, label)
            )
            entry.icon = ri.loadIcon(pm)
            result.add(entry)
        }
        result.sortBy { it.label.lowercase() }
        apps = result
        main.post { onChanged?.invoke() }
    }
}
