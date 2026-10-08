package com.tracker.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity:ComponentActivity() {
    private lateinit var store:ExpenseStore
    private var recognizer:SpeechRecognizer?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState); store=ExpenseStore(this)
        setContent { TrackerScreen() }
    }
    private fun listen(onResult:(String)->Unit,onError:(String)->Unit) {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS));onError("Grant microphone permission, then try again.");return }
        if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)){onError("On-device speech recognizer is missing. Install an offline speech model on this phone.");return}
        recognizer?.destroy()
        recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(p0:Bundle?){};override fun onBeginningOfSpeech(){};override fun onRmsChanged(p0:Float){};override fun onBufferReceived(p0:ByteArray?){};override fun onEndOfSpeech(){};override fun onPartialResults(p0:Bundle?){};override fun onEvent(p0:Int,p1:Bundle?){}
            override fun onError(error:Int){onError("Offline speech recognition error: $error")}
            override fun onResults(results:Bundle?) { val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if(text!=null)onResult(text)else onError("No command recognized.") }
        })
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)})
    }
    override fun onDestroy(){recognizer?.destroy();store.close();super.onDestroy()}

    @Composable private fun TrackerScreen(){
        val prefs=remember { getSharedPreferences("tracker",MODE_PRIVATE) }
        var expenses by remember { mutableStateOf(store.all()) }
        var budgetText by remember { mutableStateOf(prefs.getInt("budget",1000).toString()) }
        var thresholdText by remember { mutableStateOf(prefs.getInt("threshold",300).toString()) }
        var input by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Try: I bought Coke for 45 pesos") }
        var pending by remember { mutableStateOf<ParsedExpense?>(null) }
        var editing by remember { mutableStateOf<Expense?>(null) }
        var accessKey by remember { mutableStateOf(prefs.getString("porcupine_key","").orEmpty()) }
        val budget=budgetText.toIntOrNull()?.coerceAtLeast(0)?:0
        val threshold=thresholdText.toIntOrNull()?.coerceAtLeast(0)?:0
        val spent=BudgetLogic.spentToday(expenses)
        val left=budget-spent
        fun reload(){expenses=store.all()}
        MaterialTheme {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                Text("Tracker",style=MaterialTheme.typography.headlineLarge)
                Text("Offline expense assistant · Version 0.1",style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("Today's budget",style=MaterialTheme.typography.titleMedium)
                            Text("₱${left} remaining",style=MaterialTheme.typography.headlineMedium,color=if(left<=threshold)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text("₱$spent spent / ₱$budget budget")
                            LinearProgressIndicator(progress={ if(budget>0)(spent.toFloat()/budget).coerceIn(0f,1f) else 0f },modifier=Modifier.fillMaxWidth())
                            if(left<=threshold) Text("⚠ Budget warning: only ₱$left remains. Review optional purchases.",color=MaterialTheme.colorScheme.error)
                        } }
                    }
                    item {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(budgetText,{budgetText=it.filter(Char::isDigit);prefs.edit().putInt("budget",budgetText.toIntOrNull()?:0).apply()},label={Text("Daily budget ₱")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                            OutlinedTextField(thresholdText,{thresholdText=it.filter(Char::isDigit);prefs.edit().putInt("threshold",thresholdText.toIntOrNull()?:0).apply()},label={Text("Warn at ₱")},modifier=Modifier.weight(1f),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                        }
                    }
                    item {
                        OutlinedTextField(input,{input=it},label={Text("Describe an expense")},modifier=Modifier.fillMaxWidth())
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={pending=CommandParser.parse(input);if(pending==null)status="Couldn't find a valid peso amount."}){Text("Interpret")}
                            OutlinedButton(onClick={listen(onResult={input=it;pending=CommandParser.parse(it);status="Heard: $it"},onError={status=it})}) {Text("🎤 Speak")}
                        }
                        Text(status,style=MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Card {Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("Always-ready wake word (experimental)",style=MaterialTheme.typography.titleMedium)
                            Text("Requires your own Picovoice AccessKey and an Android hey_tracker.ppn model in app/src/main/assets. Start while this screen is visible.",style=MaterialTheme.typography.bodySmall)
                            OutlinedTextField(accessKey,{accessKey=it},label={Text("Picovoice access key")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={
                                    prefs.edit().putString("porcupine_key",accessKey).apply()
                                    if(ContextCompat.checkSelfPermission(this@MainActivity,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
                                        try { ContextCompat.startForegroundService(this@MainActivity,Intent(this@MainActivity,WakeService::class.java));status="Wake service starting (check Android notification)." } catch(e:Exception){status="Could not start: ${e.message}"}
                                    } else { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS));status="Grant permission and tap Start again." }
                                }) {Text("Start wake service")}
                                OutlinedButton(onClick={stopService(Intent(this@MainActivity,WakeService::class.java))}){Text("Stop")}
                            }
                        }}
                    }
                    item {Text("Last 7 days: ₱${BudgetLogic.week(expenses).sumOf{it.amount}}",style=MaterialTheme.typography.titleMedium);Text(BudgetLogic.tip(BudgetLogic.week(expenses))) }
                    item {Text("Expense history",style=MaterialTheme.typography.titleLarge)}
                    items(expenses,key={it.id}) { e -> Card {Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)){Text("${e.item} · ${e.category}");Text(e.day,style=MaterialTheme.typography.bodySmall)}
                        Text("₱${e.amount}"); TextButton(onClick={editing=e}){Text("Edit")}
                    }} }
                }
            }
            if(pending!=null) { val v=pending!!; AlertDialog(onDismissRequest={pending=null},title={Text("Confirm expense")},text={Text("Save ₱${v.amount} · ${v.category} · ${v.item}?")},confirmButton={TextButton(onClick={store.add(v.amount,v.category,v.item);reload();pending=null;input="";status="Saved offline."}){Text("Save")}},dismissButton={TextButton(onClick={pending=null}){Text("Cancel")}}) }
            if(editing!=null){val e=editing!!;var amount by remember(e.id){mutableStateOf(e.amount.toString())};var category by remember(e.id){mutableStateOf(e.category)};var item by remember(e.id){mutableStateOf(e.item)}
                AlertDialog(onDismissRequest={editing=null},title={Text("Edit expense")},text={Column {OutlinedTextField(amount,{amount=it.filter(Char::isDigit)},label={Text("Amount")});OutlinedTextField(category,{category=it},label={Text("Category")});OutlinedTextField(item,{item=it},label={Text("Item")})}},confirmButton={TextButton(enabled=(amount.toIntOrNull()?:0)>0 && category.isNotBlank(),onClick={store.update(e.id,amount.toInt(),category,item);reload();editing=null}){Text("Save")}},dismissButton={Row {TextButton(onClick={store.delete(e.id);reload();editing=null}){Text("Delete")};TextButton(onClick={editing=null}){Text("Cancel")}}})
            }
        }
    }
}
