package fr.perso.minilauncher

import android.app.Application

class LauncherApp : Application() {
    lateinit var store: GroupStore
        private set
    lateinit var repo: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        store = GroupStore(this)
        repo = AppRepository(this)
        Thread { repo.reload() }.start()
    }
}
