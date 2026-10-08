package com.tracker.offline

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ai.picovoice.porcupine.PorcupineManager
import android.content.pm.ServiceInfo

/** The microphone service is started only from a visible Activity. It does not auto-restart. */
class WakeService: Service() {
    private var manager: PorcupineManager? = null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        val channel=NotificationChannel("wake", "Tracker voice assistant", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") { stopSelf(); return START_NOT_STICKY }
        val n=Notification.Builder(this,"wake").setContentTitle("Tracker is listening")
          .setContentText("Say Hey Tracker · Tap to open Tracker")
          .setSmallIcon(android.R.drawable.ic_btn_speak_now)
          .setContentIntent(PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
          .build()
        if(Build.VERSION.SDK_INT>=34) startForeground(11,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(11,n)
        val prefs=getSharedPreferences("tracker",MODE_PRIVATE)
        val key=prefs.getString("porcupine_key","").orEmpty()
        if(key.isBlank()) { stopSelf(); return START_NOT_STICKY }
        try {
            manager?.stop(); manager?.delete()
            manager=PorcupineManager.Builder().setAccessKey(key).setKeywordPath("hey_tracker.ppn")
                .build(this) { _ ->
                    sendBroadcast(Intent("com.tracker.offline.WAKE_DETECTED").setPackage(packageName))
                }
            manager?.start()
        } catch(e:Exception) { android.util.Log.e("TrackerWake","Wake model initialization failed",e); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { try { manager?.stop();manager?.delete() } catch(_:Exception){};manager=null;super.onDestroy() }
}
