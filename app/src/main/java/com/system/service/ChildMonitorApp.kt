package com.system.service

import android.app.Application
import com.system.service.core.CrashLogger

class ChildMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
