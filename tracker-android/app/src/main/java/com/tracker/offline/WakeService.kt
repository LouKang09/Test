package com.tracker.offline

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale

/**
 * One continuous, entirely offline microphone stream.
 * The service begins while the app is visible after microphone permission is granted.
 * Android may stop it under background/battery policy; no unsupported boot auto-start.
 */
class WakeService:Service(), RecognitionListener {
    private var speech:SpeechService?=null
    private var recognizer:Recognizer?=null
    private var model:Model?=null
    private var tts:TextToSpeech?=null
    @Volatile private var running=true
    private var armedUntil=0L
    private var lastSavedAt=0L
    private val wake = Regex("""\b(?:hey|hi|hay|he)\s+(?:tracker|track her|trackers)\b""",RegexOption.IGNORE_CASE)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        val channel=NotificationChannel("wake-v2","Offline voice listener",NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        tts=TextToSpeech(this) { code -> if(code==TextToSpeech.SUCCESS) tts?.language=Locale.US }
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {
            getSharedPreferences("tracker",MODE_PRIVATE).edit().putBoolean("voice_enabled",false).apply()
            running=false;stopSelf();return START_NOT_STICKY
        }
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,2,Intent(this,WakeService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"wake-v2").setContentTitle("Tracker · offline listening")
            .setContentText("Say Hey Tracker, then your expense")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true)
            .setContentIntent(open).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stop).build()
        if(Build.VERSION.SDK_INT>=34) startForeground(21,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(21,notification)
        if(speech==null) {
            running=true
            TrackerEvents.voiceStatus.value="Preparing offline voice model…"
            Thread { initRecognizer() }.start()
        }
        return START_NOT_STICKY
    }
    private fun copyModelAssets(assetPath:String,dest:File) {
        val children=assets.list(assetPath) ?: emptyArray()
        if(children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } else {
            dest.mkdirs()
            for(child in children) copyModelAssets(assetPath+"/"+child,File(dest,child))
        }
    }
    private fun initRecognizer() {
        try {
            val dir=File(filesDir,"vosk-model-small-en-us-0.15")
            val marker=File(dir,".tracker-ready-v2")
            if(!marker.exists()) {
                if(dir.exists()) dir.deleteRecursively()
                copyModelAssets("vosk-model-small-en-us-0.15",dir)
                if(!File(dir,"am/final.mdl").exists()) error("Offline model missing from APK")
                marker.writeText("ready")
            }
            if(!running) return
            val m=Model(dir.absolutePath)
            val rec=Recognizer(m,16000.0f)
            val s=SpeechService(rec,16000.0f)
            if(!running) {s.shutdown();rec.close();m.close();return}
            model=m;recognizer=rec;speech=s
            s.startListening(this)
            TrackerEvents.voiceStatus.value="Listening for Hey Tracker"
        } catch(e:Exception) {
            Log.e("TrackerWake","Offline voice initialization failed",e)
            TrackerEvents.voiceStatus.value="Offline voice unavailable: "+(e.message ?: "unknown error")
            stopSelf()
        }
    }
    private fun extract(json:String, key:String):String {
        return try { JSONObject(json).optString(key,"").trim().lowercase() } catch(_:Exception) {""}
    }
    override fun onPartialResult(hypothesis:String?) {
        val txt=extract(hypothesis ?: "","partial")
        if(txt.isBlank())return
        val hit=wake.find(txt)
        if(hit!=null) {
            armedUntil=System.currentTimeMillis()+12000
            TrackerEvents.voiceStatus.value="Wake phrase heard · speak your expense"
            TrackerEvents.heard.value=txt
        } else if(System.currentTimeMillis()<armedUntil) {
            TrackerEvents.heard.value=txt
        }
    }
    override fun onResult(hypothesis:String?) { handleFinal(extract(hypothesis ?: "","text")) }
    override fun onFinalResult(hypothesis:String?) { handleFinal(extract(hypothesis ?: "","text")) }
    private fun handleFinal(txt:String) {
        if(txt.isBlank()) return
        val hit=wake.find(txt)
        if(hit!=null) {
            armedUntil=System.currentTimeMillis()+12000
            TrackerEvents.voiceStatus.value="Wake phrase heard · listening for expense"
        }
        if(System.currentTimeMillis()>=armedUntil) return
        val command=if(hit!=null) txt.substring(hit.range.last+1).trim() else txt
        if(command.isBlank())return
        val parsed=CommandParser.parse(command)
        TrackerEvents.heard.value=command
        if(parsed==null) { TrackerEvents.voiceStatus.value="Heard: "+command+" · say an amount in pesos";return }
        if(System.currentTimeMillis()-lastSavedAt<2500L)return
        lastSavedAt=System.currentTimeMillis()
        armedUntil=0
        try {
            ExpenseStore(this).use { it.add(parsed.amount,parsed.category,parsed.item) }
            TrackerEvents.saved()
            val spent=ExpenseStore(this).use { BudgetLogic.spentToday(it.all()) }
            val prefs=getSharedPreferences("tracker",MODE_PRIVATE)
            val remain=prefs.getInt("budget",1000)-spent
            TrackerEvents.voiceStatus.value="Saved ₱"+parsed.amount+" · "+parsed.item
            tts?.speak("Saved "+parsed.amount+" pesos for "+parsed.item+". "+remain+" pesos remaining.",TextToSpeech.QUEUE_FLUSH,null,"saved")
        } catch(e:Exception) {TrackerEvents.voiceStatus.value="Could not save: "+e.message}
    }
    override fun onError(e:Exception?) {
        TrackerEvents.voiceStatus.value="Voice error: "+(e?.message ?: "unknown")
        Log.e("TrackerWake","Recognition failed",e)
        stopSelf()
    }
    override fun onTimeout() { TrackerEvents.voiceStatus.value="Listener timed out";stopSelf() }
    override fun onDestroy() {
        running=false
        try {speech?.stop();speech?.shutdown()} catch(_:Exception){}
        try {recognizer?.close()} catch(_:Exception){}
        try {model?.close()} catch(_:Exception){}
        tts?.stop();tts?.shutdown()
        speech=null;model=null;recognizer=null
        TrackerEvents.voiceStatus.value="Voice listener stopped"
        super.onDestroy()
    }
}
