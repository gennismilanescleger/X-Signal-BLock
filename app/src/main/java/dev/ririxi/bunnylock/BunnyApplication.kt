package dev.ririxi.bunnylock

import android.app.Application
import android.content.res.Configuration
import dev.ririxi.bunnylock.data.AppLanguage
import dev.ririxi.bunnylock.signalx.CrashLog

class BunnyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Fase 6: install the crash log first, before anything that can
        // fail. It only writes a file — it never breaks the app.
        CrashLog.install(this)
        AppLanguage.apply(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.apply(this)
    }
}
