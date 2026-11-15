package fr.perso.minilauncher

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Compte les notifications en attente (nécessite l'autorisation « Accès aux notifications »). */
class NotifService : NotificationListenerService() {

    companion object {
        @Volatile var count = 0
        @Volatile var connected = false
        var onChange: (() -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())
    }

    private fun recount() {
        count = try {
            (activeNotifications ?: emptyArray<StatusBarNotification>()).count {
                !it.isOngoing &&
                    (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                    it.packageName != packageName
            }
        } catch (e: Exception) { 0 }
        main.post { onChange?.invoke() }
    }

    override fun onListenerConnected() { connected = true; recount() }
    override fun onListenerDisconnected() { connected = false; main.post { onChange?.invoke() } }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()
}
