package fr.perso.minilauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable

/** Une appli lançable. L'intention de lancement est préparée une fois pour toutes (lancement sans latence). */
class AppEntry(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val defaultGroup: String
) {
    val key: String = component.flattenToString()

    val launchIntent: Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    @Volatile
    var icon: Drawable? = null
}
