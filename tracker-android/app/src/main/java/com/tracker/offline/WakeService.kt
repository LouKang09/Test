package com.tracker.offline

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ai.picovoice.porcupine.PorcupineManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Offline dedicated wake detector + network-capable Android speech service + optional AI backend. */
class WakeService: Service() {
    private val ui=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tracker",MODE_PRIVATE) }
    private var wake:PorcupineManager?=null
    private var stt:SpeechRecognizer?=null
    private var voice:TextToSpeech?=null
    private var enabled=false
    private var recognizing=false
    private var lastSavedAt=0L
    override fun onBind(intent:Intent?):IBinder?=null
    private fun state(message:String,error:String="") {
        ui.post { TrackerEvents.voiceStatus.value=message;TrackerEvents.error.value=error }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracker-hybrid-v4","Tracker hands-free voice",NotificationManager.IMPORTANCE_LOW))
        voice=TextToSpeech(this) { result->
            if(result==TextToSpeech.SUCCESS) voice?.language=Locale.forLanguageTag("en-PH")
        }
        voice?.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
            override fun onStart(id:String?){}
            override fun onDone(id:String?) { ui.postDelayed({ resumeWake() },500) }
            override fun onError(id:String?) { ui.postDelayed({ resumeWake() },500) }
        })
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {enabled=false;prefs.edit().putBoolean("voice_enabled",false).apply();stopSelf();return START_NOT_STICKY}
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,2,Intent(this,WakeService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=Notification.Builder(this,"tracker-hybrid-v4").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Tracker · Hybrid Assistant").setContentText("On-device wake phrase · online speech commands")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stop).build()
        if(Build.VERSION.SDK_INT>=34) startForeground(44,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(44,n)
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            state("Microphone permission required");stopSelf();return START_NOT_STICKY
        }
        enabled=true
        if(intent?.action=="TEST") {
            pauseWake();ui.postDelayed({beginSpeech()},350)
        } else {
            ui.post{resumeWake()}
        }
        return START_NOT_STICKY
    }
    private fun pauseWake() {
        try { wake?.stop() }catch(e:Exception){Log.w("Tracker","Pause wake failed",e)}
        TrackerEvents.micActive.value=false
    }
    private fun resumeWake() {
        if(!enabled || recognizing || !prefs.getBoolean("voice_enabled",true))return
        val key=prefs.getString("picovoice_key","").orEmpty().trim()
        val file=File(filesDir,"hey_tracker_android.ppn")
        if(key.isEmpty() || !file.isFile) {
            TrackerEvents.micActive.value=false
            state("Wake setup needed","Import an Android Hey Tracker .ppn file and enter Picovoice AccessKey in Settings.")
            return
        }
        try {
            if(wake==null) {
                wake=PorcupineManager.Builder().setAccessKey(key)
                    .setKeywordPath(file.absolutePath)
                    .setSensitivity(prefs.getFloat("wake_sensitivity",0.7f))
                    .build(this){_ -> ui.post {
                        if(enabled && !recognizing) {
                            TrackerEvents.wakeEvents.intValue++
                            TrackerEvents.wakePreview.value="Hey Tracker recognized"
                            state("Wake detected · speak your expense")
                            pauseWake()
                            ui.postDelayed({beginSpeech()},420)
                        }
                    }}
            }
            wake?.start()
            TrackerEvents.micActive.value=true
            state("Ready · say Hey Tracker (offline wake)")
        } catch(e:Exception) {
            TrackerEvents.micActive.value=false
            state("Wake detection failed",e.message.orEmpty())
        }
    }
    private fun finishSpeech(restart:Boolean=true) {
        recognizing=false
        try{stt?.cancel();stt?.destroy()}catch(_:Exception){}
        stt=null
        TrackerEvents.micLevel.intValue=0
        TrackerEvents.micActive.value=false
        if(restart)ui.postDelayed({resumeWake()},900)
    }
    private fun beginSpeech() {
        if(!enabled)return
        if(recognizing)return
        if(!SpeechRecognizer.isRecognitionAvailable(this)) {
            state("No speech recognition service","Enable Google speech services or the device's default speech recognizer.")
            resumeWake();return
        }
        recognizing=true
        try {
            val recognizer=SpeechRecognizer.createSpeechRecognizer(this)
            stt=recognizer
            val locale=prefs.getString("speech_locale","en-PH").orEmpty()
            recognizer.setRecognitionListener(object:RecognitionListener {
                override fun onReadyForSpeech(bundle:Bundle?){state("Speak now · network-capable speech")}
                override fun onBeginningOfSpeech(){state("Hearing your expense…")}
                override fun onRmsChanged(rms:Float){TrackerEvents.micLevel.intValue=((rms+2)*9).toInt().coerceIn(0,100)}
                override fun onBufferReceived(buffer:ByteArray?){}
                override fun onEndOfSpeech(){state("Recognizing expense…")}
                override fun onError(err:Int){
                    val reason=when(err){
                        SpeechRecognizer.ERROR_NETWORK->"Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT->"Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH->"No matching speech"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT->"No speech heard"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY->"Recognizer busy"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS->"Microphone denied"
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED->"Language not supported"
                        else->"Speech error "+err
                    }
                    state(reason,"Try the Test Online Voice button in Settings, or change the speech language.")
                    finishSpeech()
                }
                override fun onResults(results:Bundle?){
                    val transcript=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    TrackerEvents.heard.value=transcript
                    finishSpeech(false)
                    if(transcript.isEmpty()){state("Nothing recognized");ui.postDelayed({resumeWake()},900)}
                    else interpret(transcript)
                }
                override fun onPartialResults(results:Bundle?){
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let{TrackerEvents.heard.value=it}
                }
                override fun onEvent(type:Int,bundle:Bundle?){}
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,locale)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,locale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
            })
            state("Online recognizer started ("+locale+")")
            ui.postDelayed({
                if(stt===recognizer && recognizing){state("Speech timeout","Try another command");finishSpeech()}
            },13000)
        }catch(e:Exception){state("Speech startup error",e.message.orEmpty());finishSpeech()}
    }
    private fun interpret(words:String) {
        state("Interpreting: "+words.take(60))
        val endpoint=prefs.getString("tracker_api","").orEmpty().trim().trimEnd('/')
        val token=prefs.getString("tracker_token","").orEmpty().trim()
        Thread{
            var parsed:ParsedExpense?=null
            if(endpoint.startsWith("https://") && token.isNotEmpty()) {
                try {
                    val conn=URL(endpoint+"/interpret").openConnection() as HttpURLConnection
                    conn.connectTimeout=7000;conn.readTimeout=12000
                    conn.requestMethod="POST";conn.doOutput=true
                    conn.setRequestProperty("Content-Type","application/json")
                    conn.setRequestProperty("X-Tracker-Token",token)
                    conn.outputStream.use{it.write(JSONObject().put("text",words).toString().toByteArray())}
                    if(conn.responseCode in 200..299) {
                        val result=JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                        if(result.optString("action")=="expense") {
                            val n=result.optInt("amount")
                            if(n in 1..1000000)parsed=ParsedExpense(n,result.optString("category","Other").take(40),result.optString("item","Expense").take(80))
                        }
                    }
                    conn.disconnect()
                }catch(e:Exception){Log.w("Tracker","AI interpretation unavailable, using local rules",e)}
            }
            if(parsed==null)parsed=CommandParser.parse(words)
            val exp=parsed
            ui.post {
                if(!enabled)return@post
                if(exp==null) {
                    state("No expense amount understood","Heard: "+words)
                    ui.postDelayed({resumeWake()},900)
                } else if(SystemClock.elapsedRealtime()-lastSavedAt>1800) {
                    lastSavedAt=SystemClock.elapsedRealtime()
                    try {
                        ExpenseStore(this).use{db->
                            db.add(exp.amount,exp.category,exp.item)
                            val left=prefs.getInt("budget",1000)-BudgetLogic.spentToday(db.all())
                            TrackerEvents.saved()
                            state("Saved ₱"+exp.amount+" · "+exp.item)
                            val utterance="Saved "+exp.amount+" pesos for "+exp.item+". "+left+" pesos left."
                            val result=voice?.speak(utterance,TextToSpeech.QUEUE_FLUSH,null,"saved")
                            if(result!=TextToSpeech.SUCCESS)ui.postDelayed({resumeWake()},800)
                        }
                    }catch(e:Exception){state("Save failed",e.message.orEmpty());ui.postDelayed({resumeWake()},800)}
                } else ui.postDelayed({resumeWake()},900)
            }
        }.start()
    }
    override fun onDestroy(){
        enabled=false
        finishSpeech(false)
        try{wake?.stop();wake?.delete()}catch(_:Exception){}
        wake=null
        voice?.stop();voice?.shutdown()
        TrackerEvents.micActive.value=false
        super.onDestroy()
    }
}
