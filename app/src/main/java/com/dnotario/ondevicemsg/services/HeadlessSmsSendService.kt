package com.dnotario.ondevicemsg.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dummy implementation - required for default SMS app
        stopSelf()
        return START_NOT_STICKY
    }
}