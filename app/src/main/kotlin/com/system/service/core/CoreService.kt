package com.system.service.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class CoreService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.d("CoreService", "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CoreService", "Service Started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("CoreService", "Service Destroyed")
    }
}
