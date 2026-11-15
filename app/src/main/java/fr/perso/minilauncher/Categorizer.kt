package fr.perso.minilauncher

import android.content.pm.ApplicationInfo

/** Rangement par défaut des applis dans 4 groupes. L'utilisateur peut ensuite tout changer. */
object Categorizer {
    const val COMM = "Communiquer"
    const val MEDIA = "Médias"
    const val TOOLS = "Outils"
    const val WEB = "Web"

    val defaultGroups: List<String> = listOf(COMM, MEDIA, TOOLS, WEB)

    private val webKeys = listOf(
        "chrome", "firefox", "browser", "brave", "opera", "edge", "duckduckgo", "vivaldi", "navigateur"
    )
    private val commKeys = listOf(
        "dialer", "telephon", "contacts", "contact", "messag", "sms", "mms", "mail", "gmail",
        "whatsapp", "signal", "telegram", "messenger", "outlook", "discord", "skype", "teams", "zoom"
    )
    private val mediaKeys = listOf(
        "camera", "appareil photo", "gallery", "galerie", "photos", "music", "musique", "spotify",
        "youtube", "video", "vlc", "podcast", "deezer", "netflix", "radio", "player"
    )

    private fun matches(keys: List<String>, pkg: String, label: String): Boolean =
        keys.any { pkg.contains(it) || label.contains(it) }

    fun defaultGroupFor(info: ApplicationInfo, label: String): String {
        val p = info.packageName.lowercase()
        val l = label.lowercase()
        if (matches(webKeys, p, l)) return WEB
        if (matches(commKeys, p, l)) return COMM
        if (matches(mediaKeys, p, l)) return MEDIA
        return when (info.category) {
            ApplicationInfo.CATEGORY_SOCIAL -> COMM
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_IMAGE,
            ApplicationInfo.CATEGORY_GAME -> MEDIA
            ApplicationInfo.CATEGORY_NEWS -> WEB
            else -> TOOLS
        }
    }
}
