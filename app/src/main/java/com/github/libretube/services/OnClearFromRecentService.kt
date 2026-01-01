package com.github.libretube.services

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.content.getSystemService
import com.github.libretube.enums.NotificationId
import com.github.libretube.helpers.BackgroundHelper
import kotlinx.coroutines.launch

class OnClearFromRecentService : Service() {
    private var nManager: NotificationManager? = null

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        nManager = getSystemService<NotificationManager>()
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Auto-Export when app is removed from recent tasks
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch {
            com.github.libretube.helpers.SafAutoSyncHelper.exportData(applicationContext)
        }

        super.onTaskRemoved(rootIntent)
        stopSelf()
    }
}
