package com.tracker.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Context
import java.io.File
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Navy=Color(0xFF0A1423)
private val Panel=Color(0xFF142339)
private val Panel2=Color(0xFF1B2D47)
private val Mint=Color(0xFF87F4D0)
private val Indigo=Color(0xFF8EAAFC)
private val Soft=Color(0xFF9AADC5)
private val Peach=Color(0xFFFFC08E)
private val White=Color(0xFFF0F5FF)
private val Coral=Color(0xFFFF8C88)
private val BudgetBlue=Color(0xFF5A88E8)

private val trackerColors=darkColorScheme(
    primary=Mint,onPrimary=Navy,secondary=Indigo,
    background=Navy,onBackground=White,
    surface=Panel,onSurface=White,
    surfaceVariant=Panel2,onSurfaceVariant=Soft,
    outline=Color(0xFF354861),error=Coral
)

class MainActivity:ComponentActivity() {
    private lateinit var store:ExpenseStore
    private val pickWakeModel=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            val model=File(filesDir,"hey_tracker_android.ppn")
            try {
                var count=0
                contentResolver.openInputStream(uri)?.use {input ->
                    model.outputStream().use {output ->
                        val buf=ByteArray(8192)
                        while(true){
                            val n=input.read(buf)
                            if(n<0)break
                            count+=n
                            if(count>5_000_000)throw IllegalStateException("File exceeds 5 MB")
                            output.write(buf,0,n)
                        }
                    }
                } ?: throw IllegalStateException("Could not open model")
                if(count<1024)throw IllegalStateException("Model file too small")
                TrackerEvents.configRevision.intValue++
                TrackerEvents.voiceStatus.value="Hey Tracker model imported"
                stopService(Intent(this,WakeService::class.java))
            }catch(e:Exception){
                model.delete()
                TrackerEvents.voiceStatus.value="Model import failed: "+e.message
            }
        }
    }
    private val requestMic=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if(result[Manifest.permission.RECORD_AUDIO]==true) startVoice()
        else TrackerEvents.voiceStatus.value="Microphone permission required for Hey Tracker"
    }
    private fun hasMic():Boolean =
        ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun startVoice() {
        if(!getSharedPreferences("tracker",MODE_PRIVATE).getBoolean("voice_enabled",true))return
        if(!hasMic())return
        try {
            ContextCompat.startForegroundService(this,Intent(this,WakeService::class.java))
            TrackerEvents.voiceStatus.value="Starting offline listener…"
        } catch(e:Exception) { TrackerEvents.voiceStatus.value="Can't start microphone: "+(e.message ?: "restricted by Android") }
    }
    private fun voiceEnabled(enabled:Boolean) {
        getSharedPreferences("tracker",MODE_PRIVATE).edit().putBoolean("voice_enabled",enabled).apply()
        if(!enabled) {
            stopService(Intent(this,WakeService::class.java))
            TrackerEvents.voiceStatus.value="Voice listener disabled"
        } else if(hasMic()) startVoice()
        else requestMic.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))
    }
    private fun testOnlineVoice(){
        if(!hasMic()){
            requestMic.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        getSharedPreferences("tracker",MODE_PRIVATE).edit().putBoolean("voice_enabled",true).apply()
        try{
            ContextCompat.startForegroundService(this,Intent(this,WakeService::class.java).setAction("TEST"))
        }catch(e:Exception){TrackerEvents.voiceStatus.value="Speech test error: "+e.message}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=ExpenseStore(this)
        setContent {
            MaterialTheme(colorScheme=trackerColors) {
                TrackerApp(store=store,prefsBudget={getSharedPreferences("tracker",MODE_PRIVATE).getInt("budget",1000)},
                    prefsThreshold={getSharedPreferences("tracker",MODE_PRIVATE).getInt("threshold",300)},
                    saveBudgets={budget,threshold->
                        getSharedPreferences("tracker",MODE_PRIVATE).edit().putInt("budget",budget).putInt("threshold",threshold).apply()
                    }, enabledPref={getSharedPreferences("tracker",MODE_PRIVATE).getBoolean("voice_enabled",true)},
                    toggleVoice={voiceEnabled(it)}, importWakeModel={pickWakeModel.launch(arrayOf("*/*"))}, testVoice={testOnlineVoice()})
            }
        }
        if(getSharedPreferences("tracker",MODE_PRIVATE).getBoolean("voice_enabled",true)) {
            if(hasMic()) startVoice()
            else requestMic.launch(arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS))
        }
    }
    override fun onDestroy() { store.close();super.onDestroy() }
}

private fun peso(n:Int):String = "₱"+String.format("%,d",n)
private fun categoryColor(name:String):Color = when(name) {
    "Food"->Peach
    "Transportation"->Mint
    "Bills"->Indigo
    "Shopping"->Color(0xFFD4A5F8)
    "Health"->Coral
    else->Color(0xFF79C3FA)
}
private fun categoryIcon(name:String):String = when(name) {
    "Food"->"🍜"
    "Transportation"->"🚕"
    "Bills"->"🧾"
    "Shopping"->"🛍"
    "Health"->"💊"
    else->"💸"
}
@Composable private fun RoundedPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Column(modifier.background(Panel,RoundedCornerShape(24.dp)).padding(18.dp),content=content)
}
@Composable private fun SmallTitle(text:String,action:String?=null,onAction:(()->Unit)?=null) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(text,color=White,fontSize=18.sp,fontWeight=FontWeight.Bold)
        if(action!=null && onAction!=null) Text(action,Modifier.clickable { onAction() },color=Mint,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
    }
}
@Composable private fun Metric(label:String,value:String,accent:Color,modifier:Modifier=Modifier,sub:String="") {
    RoundedPanel(modifier) {
        Box(Modifier.size(32.dp).background(accent.copy(alpha=.13f),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
            Text("●",color=accent,fontSize=18.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(value,fontSize=23.sp,color=White,fontWeight=FontWeight.Bold,maxLines=1)
        Text(label,color=Soft,fontSize=12.sp)
        if(sub.isNotBlank())Text(sub,color=accent,fontSize=11.sp)
    }
}
@Composable private fun DonutChart(spend:Map<String,Int>,modifier:Modifier=Modifier) {
    val total=spend.values.sum().coerceAtLeast(1)
    Box(modifier,contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(134.dp)) {
            val w=17.dp.toPx()
            drawArc(Panel2,-90f,360f,false,style=Stroke(w,cap=StrokeCap.Round))
            var start=-90f
            spend.forEach { (name,value)->
                val angle=360f*value/total
                if(angle>0.5f) drawArc(categoryColor(name),start, (angle-2.5f).coerceAtLeast(.5f),false,style=Stroke(w,cap=StrokeCap.Round))
                start+=angle
            }
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text(peso(spend.values.sum()),color=White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            Text("7-day total",color=Soft,fontSize=10.sp)
        }
    }
}
@Composable private fun TransactionRow(e:Expense,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable { onClick() }.padding(vertical=9.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(categoryColor(e.category).copy(alpha=.14f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
            Text(categoryIcon(e.category),fontSize=21.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(e.item,color=White,fontWeight=FontWeight.SemiBold,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(e.category+" · "+e.day,color=Soft,fontSize=11.sp)
        }
        Text("-"+peso(e.amount),color=White,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
    }
}
@Composable private fun TrackerApp(
    store:ExpenseStore,
    prefsBudget:()->Int,
    prefsThreshold:()->Int,
    saveBudgets:(Int,Int)->Unit,
    enabledPref:()->Boolean,
    toggleVoice:(Boolean)->Unit,
    importWakeModel:()->Unit,
    testVoice:()->Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var budget by remember { mutableIntStateOf(prefsBudget()) }
    var threshold by remember { mutableIntStateOf(prefsThreshold()) }
    var voiceEnabled by remember { mutableStateOf(enabledPref()) }
    val appContext=LocalContext.current
    val p=remember {appContext.getSharedPreferences("tracker",Context.MODE_PRIVATE)}
    val configRevision=TrackerEvents.configRevision.intValue
    val modelReady=remember(configRevision) { File(appContext.filesDir,"hey_tracker_android.ppn").isFile }
    var accessKey by remember { mutableStateOf(p.getString("picovoice_key","").orEmpty()) }
    var locale by remember { mutableStateOf(p.getString("speech_locale","en-PH").orEmpty()) }
    var apiUrl by remember { mutableStateOf(p.getString("tracker_api","").orEmpty()) }
    var apiToken by remember { mutableStateOf(p.getString("tracker_token","").orEmpty()) }
    var showAdd by remember { mutableStateOf(false) }
    var editExpense by remember { mutableStateOf<Expense?>(null) }
    var changeBudget by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val revision=TrackerEvents.revision.intValue
    val all=remember(revision) { store.all() }
    val today=BudgetLogic.spentToday(all)
    val left=budget-today
    val week=BudgetLogic.week(all)
    val weekTotal=week.sumOf{it.amount}
    val byCategory=week.groupBy { it.category }.mapValues { it.value.sumOf { e->e.amount } }.toList().sortedByDescending { it.second }.toMap()
    val voiceStatus=TrackerEvents.voiceStatus.value
    val lastHeard=TrackerEvents.heard.value
    val micLevel=TrackerEvents.micLevel.intValue
    val micActive=TrackerEvents.micActive.value
    val wakePreview=TrackerEvents.wakePreview.value
    val wakeCount=TrackerEvents.wakeEvents.intValue
    val voiceError=TrackerEvents.error.value
    val greeting=when(LocalTime.now().hour) {in 5..11->"GOOD MORNING";in 12..17->"GOOD AFTERNOON";else->"GOOD EVENING"}
    Column(Modifier.fillMaxSize().background(Navy)) {
        Row(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,top=21.dp,bottom=15.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).background(Mint,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
                    Text("✦",fontSize=24.sp,color=Navy)
                }
                Column {
                    Text("TRACKER",fontSize=19.sp,fontWeight=FontWeight.ExtraBold,color=White,letterSpacing=1.sp)
                    Text("Your offline money companion",fontSize=10.sp,color=Soft)
                }
            }
            Box(Modifier.size(38.dp).background(Panel2,CircleShape).clickable { tab=3 },contentAlignment=Alignment.Center) {
                Text("⚙",fontSize=21.sp,color=White)
            }
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=18.dp,end=18.dp,bottom=22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(tab==0) {
                item {
                    Text(greeting,color=Soft,fontSize=11.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.5.sp)
                    Text("Your money, in focus.",color=White,fontSize=25.sp,fontWeight=FontWeight.Bold)
                    Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")),color=Soft,fontSize=12.sp)
                }
                item {
                    Column(Modifier.fillMaxWidth().background(Panel2,RoundedCornerShape(24.dp)).padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text("AVAILABLE TODAY",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Soft,letterSpacing=1.sp)
                            Text("Edit ↗",Modifier.clickable {changeBudget=true},fontSize=12.sp,color=Mint)
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(peso(left),color=if(left<=threshold)Peach else Mint,fontSize=39.sp,fontWeight=FontWeight.ExtraBold)
                        Text("remaining from your "+peso(budget)+" budget",fontSize=12.sp,color=Soft)
                        Spacer(Modifier.height(20.dp))
                        LinearProgressIndicator(progress={if(budget>0)(today.toFloat()/budget).coerceIn(0f,1f) else 0f},
                            modifier=Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(12.dp)),
                            color=if(left<=threshold)Peach else Mint,trackColor=Color(0xFF33445B))
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Spent "+peso(today),fontSize=12.sp,color=White)
                            Text("Budget "+peso(budget),fontSize=12.sp,color=Soft)
                        }
                    }
                }
                if(left<=threshold) item {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF49352B),RoundedCornerShape(18.dp)).padding(15.dp),
                        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("⚠",fontSize=24.sp,color=Peach)
                        Column {
                            Text(if(left<0)"Daily budget exceeded" else "You're nearing your limit",color=Peach,fontWeight=FontWeight.Bold,fontSize=14.sp)
                            Text("Only "+peso(left)+" left. Consider cutting back on frequent non-essential purchases.",color=White,fontSize=12.sp)
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Metric("Spent today",peso(today),Peach,Modifier.weight(1f))
                        Metric("This week",peso(weekTotal),Indigo,Modifier.weight(1f))
                    }
                }
                item {
                    RoundedPanel {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                Text("◉",color=Mint,fontSize=22.sp)
                                Column {
                                    Text("Hey Tracker",fontWeight=FontWeight.Bold,color=White,fontSize=16.sp)
                                    Text(if(voiceEnabled)"Voice assistant is on" else "Voice assistant is off",color=Soft,fontSize=11.sp)
                                }
                            }
                            Switch(checked=voiceEnabled,onCheckedChange={voiceEnabled=it;toggleVoice(it)},colors=SwitchDefaults.colors(checkedThumbColor=Navy,checkedTrackColor=Mint))
                        }
                        Spacer(Modifier.height(13.dp))
                        Text(if(micActive) "● Audio recording confirmed" else "○ Waiting for microphone",color=if(micActive) Mint else Peach,fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        LinearProgressIndicator(progress={micLevel/100f},modifier=Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(8.dp)),color=Mint,trackColor=Color(0xFF33445B))
                        Text("Speech-command level: "+micLevel+"%",color=Soft,fontSize=11.sp)
                        Text(voiceStatus,color=Mint,fontSize=12.sp)
                        if(voiceError.isNotEmpty()) Text(voiceError,color=Coral,fontSize=12.sp)
                        Text("Wake detector: "+wakePreview,color=Soft,fontSize=11.sp,maxLines=2)
                        Text("Last speech: "+lastHeard,color=Soft,fontSize=11.sp,maxLines=2)
                        Spacer(Modifier.height(9.dp))
                        Text("Say: Hey Tracker, save fifty five pesos for transportation",color=White,fontSize=12.sp)
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Weekly breakdown","Details →"){tab=2}
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(15.dp)) {
                            DonutChart(byCategory,Modifier.width(153.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                if(byCategory.isEmpty()) Text("Add an expense to see your spending categories.",color=Soft,fontSize=12.sp)
                                byCategory.entries.take(5).forEach { (c,n)->
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                                        Box(Modifier.size(8.dp).background(categoryColor(c),CircleShape))
                                        Column {
                                            Text(c,color=Soft,fontSize=11.sp)
                                            Text(peso(n),color=White,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SmallTitle("Recent activity","See all →"){tab=1}
                    if(all.isEmpty()) Text("No expenses yet. Say Hey Tracker to add your first!",color=Soft,fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
                }
                items(all.take(5),key={it.id}){e->TransactionRow(e){editExpense=e}}
            }
            if(tab==1) {
                item {
                    Text("Expense history",color=White,fontSize=27.sp,fontWeight=FontWeight.Bold)
                    Text(all.size.toString()+" recorded transactions · all saved offline",color=Soft,fontSize=12.sp)
                    Spacer(Modifier.height(15.dp))
                    OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("Search item or category")},singleLine=true,shape=RoundedCornerShape(17.dp))
                }
                items(all.filter { it.item.contains(search,true) || it.category.contains(search,true) || it.day.contains(search,true) },key={it.id}) { e->
                    RoundedPanel { TransactionRow(e){editExpense=e} }
                }
                if(all.isEmpty())item {Text("Your transactions will appear here.",color=Soft)}
            }
            if(tab==2) {
                item {
                    Text("Spending insights",color=White,fontSize=27.sp,fontWeight=FontWeight.Bold)
                    Text("A clearer view of the past 7 days",color=Soft,fontSize=12.sp)
                }
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Metric("7-day expenses",peso(weekTotal),Peach,Modifier.weight(1f))
                        Metric("Transactions",week.size.toString(),Indigo,Modifier.weight(1f))
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Where your money goes")
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){DonutChart(byCategory)}
                        Spacer(Modifier.height(14.dp))
                        byCategory.forEach { (category,amount)->
                            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Box(Modifier.size(10.dp).background(categoryColor(category),CircleShape))
                                    Text(category,color=White,fontSize=13.sp)
                                }
                                Text(peso(amount),color=Soft,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                            }
                        }
                    }
                }
                item {
                    RoundedPanel {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                            Text("✦",color=Mint,fontSize=23.sp)
                            Text("Smart saving tip",color=White,fontSize=17.sp,fontWeight=FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(BudgetLogic.tip(week),color=Soft,fontSize=13.sp)
                    }
                }
            }
            if(tab==3) {
                item {
                    Text("Settings",color=White,fontSize=27.sp,fontWeight=FontWeight.Bold)
                    Text("Personalize your money companion",color=Soft,fontSize=12.sp)
                }
                item {
                    RoundedPanel {
                        SmallTitle("Your daily budget")
                        Spacer(Modifier.height(13.dp))
                        Text(peso(budget),color=Mint,fontWeight=FontWeight.Bold,fontSize=31.sp)
                        Text("Alert when balance reaches "+peso(threshold),color=Soft,fontSize=13.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick={changeBudget=true},colors=ButtonDefaults.buttonColors(containerColor=Mint),shape=RoundedCornerShape(13.dp)) {
                            Text("Edit budget and threshold",color=Navy,fontWeight=FontWeight.Bold)
                        }
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Voice activation")
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Always-ready Hey Tracker",color=White,fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                                Text("Uses Picovoice for offline wake detection and Android's speech recognition service for spoken commands.",color=Soft,fontSize=12.sp)
                            }
                            Switch(checked=voiceEnabled,onCheckedChange={voiceEnabled=it;toggleVoice(it)},colors=SwitchDefaults.colors(checkedThumbColor=Navy,checkedTrackColor=Mint))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(voiceStatus,color=Mint,fontSize=12.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(if(micActive) "Actual audio is being received" else "No recorded audio detected",color=if(micActive) Mint else Peach,fontSize=12.sp)
                        LinearProgressIndicator(progress={micLevel/100f},modifier=Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(8.dp)),color=Mint,trackColor=Panel2)
                        Text("Input level: "+micLevel+"% · wake matches: "+wakeCount,color=Soft,fontSize=12.sp)
                        Text("Wake decoder: "+wakePreview,color=Soft,fontSize=12.sp)
                        Text("Command heard: "+lastHeard,color=Soft,fontSize=12.sp)
                        if(voiceError.isNotEmpty()) Text("Problem: "+voiceError,color=Coral,fontSize=12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Test: speak near the phone. If the meter stays at 0%, check Android microphone access and close other recording apps. If the meter moves but the wake count stays at 0, the phrase was not recognized.",color=Soft,fontSize=12.sp)
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Wake phrase setup")
                        Spacer(Modifier.height(8.dp))
                        Text("To hear Hey Tracker without tapping, generate a custom Android .ppn file for Hey Tracker at console.picovoice.ai and get your Picovoice AccessKey.",color=Soft,fontSize=12.sp)
                        OutlinedTextField(accessKey,{accessKey=it},label={Text("Picovoice AccessKey")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
                        Spacer(Modifier.height(8.dp))
                        Text(if(modelReady)"✓ Wake-word model imported" else "No Android Hey Tracker .ppn imported",color=if(modelReady) Mint else Peach,fontSize=12.sp)
                        OutlinedButton(onClick={importWakeModel()}){Text("Import Android .ppn model",color=Mint)}
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(locale,{locale=it},label={Text("Speech language: en-PH, fil-PH, en-US")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick={
                            p.edit().putString("picovoice_key",accessKey.trim())
                                .putString("speech_locale",locale.trim().ifBlank{"en-PH"}).apply()
                            appContext.stopService(Intent(appContext,WakeService::class.java))
                            voiceEnabled=true
                            toggleVoice(true)
                        },colors=ButtonDefaults.buttonColors(containerColor=Mint)){
                            Text("Save and enable Hey Tracker",color=Navy)
                        }
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Test online voice")
                        Text("Use Android's network-capable speech recognizer without setting up the wake phrase.",color=Soft,fontSize=12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick={testVoice()}){Text("Test Online Voice",color=Mint)}
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Optional cloud AI endpoint")
                        Text("Enter a secure Railway server URL and app token to improve Taglish expense understanding. The OpenAI API key stays on the server, never in this APK.",color=Soft,fontSize=12.sp)
                        OutlinedTextField(apiUrl,{apiUrl=it},label={Text("Backend HTTPS URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        OutlinedTextField(apiToken,{apiToken=it},label={Text("Backend client token")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation())
                        OutlinedButton(onClick={
                            p.edit().putString("tracker_api",apiUrl.trim())
                                .putString("tracker_token",apiToken.trim()).apply()
                            TrackerEvents.voiceStatus.value="Backend connection settings saved"
                        }){Text("Save connection",color=Mint)}
                    }
                }
                item {
                    RoundedPanel {
                        SmallTitle("Privacy & storage")
                        Spacer(Modifier.height(8.dp))
                        Text("All expenses are saved in the phone's private SQLite database. No login, cloud account, or internet connection is required for everyday use.",color=Soft,fontSize=13.sp)
                        Spacer(Modifier.height(9.dp))
                        Text("Version 0.4 · Hybrid voice beta",color=Indigo,fontSize=12.sp)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal=18.dp).padding(bottom=9.dp),contentAlignment=Alignment.CenterEnd) {
            Button(onClick={showAdd=true},shape=RoundedCornerShape(18.dp),colors=ButtonDefaults.buttonColors(containerColor=Mint),contentPadding=PaddingValues(horizontal=22.dp,vertical=14.dp)) {
                Text("＋ Add expense",color=Navy,fontWeight=FontWeight.Bold)
            }
        }
        NavigationBar(containerColor=Panel,tonalElevation=0.dp) {
            listOf("Home","History","Insights","Settings").forEachIndexed { i,name ->
                NavigationBarItem(selected=tab==i,onClick={tab=i},icon={
                    Text(listOf("⌂","▤","◔","⚙")[i],fontSize=24.sp,color=if(tab==i)Navy else Soft)
                },label={Text(name,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Navy,selectedTextColor=Mint,indicatorColor=Mint,unselectedIconColor=Soft,unselectedTextColor=Soft))
            }
        }
    }
    if(showAdd) {
        var prompt by remember {mutableStateOf("")}
        val parsed=CommandParser.parse(prompt)
        AlertDialog(
            onDismissRequest={showAdd=false},
            title={Text("Add an expense")},
            text={
                Column {
                    Text("Describe your purchase",color=Soft,fontSize=13.sp)
                    Spacer(Modifier.height(9.dp))
                    OutlinedTextField(prompt,{prompt=it},label={Text("e.g. Snacks 235 pesos")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(11.dp))
                    Text(if(parsed!=null) peso(parsed.amount)+" · "+parsed.category+" · "+parsed.item else "Enter an amount and an item.",color=if(parsed!=null)Mint else Soft,fontSize=12.sp)
                }
            },
            confirmButton={TextButton(enabled=parsed!=null,onClick={
                if(parsed!=null) {store.add(parsed.amount,parsed.category,parsed.item);TrackerEvents.saved();showAdd=false}
            }){Text("Save offline",color=Mint)}},
            dismissButton={TextButton(onClick={showAdd=false}){Text("Cancel")}},
            containerColor=Panel2
        )
    }
    if(changeBudget) {
        var budgetInput by remember {mutableStateOf(budget.toString())}
        var thresholdInput by remember {mutableStateOf(threshold.toString())}
        AlertDialog(onDismissRequest={changeBudget=false},title={Text("Daily budget")},
            text={Column {
                OutlinedTextField(budgetInput,{budgetInput=it.filter(Char::isDigit)},label={Text("Daily budget ₱")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(thresholdInput,{thresholdInput=it.filter(Char::isDigit)},label={Text("Warning at remaining ₱")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
            }},
            confirmButton={TextButton(enabled=budgetInput.toIntOrNull()!=null && thresholdInput.toIntOrNull()!=null,onClick={
                budget=budgetInput.toIntOrNull()?:budget;threshold=thresholdInput.toIntOrNull()?:threshold
                saveBudgets(budget,threshold);changeBudget=false
            }){Text("Save",color=Mint)}},
            dismissButton={TextButton(onClick={changeBudget=false}){Text("Cancel")}},containerColor=Panel2)
    }
    if(editExpense!=null) {
        val e=editExpense!!
        var a by remember(e.id){mutableStateOf(e.amount.toString())}
        var c by remember(e.id){mutableStateOf(e.category)}
        var n by remember(e.id){mutableStateOf(e.item)}
        AlertDialog(onDismissRequest={editExpense=null},title={Text("Edit transaction")},
            text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(a,{a=it.filter(Char::isDigit)},label={Text("Amount ₱")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                OutlinedTextField(n,{n=it},label={Text("Item")},singleLine=true)
                OutlinedTextField(c,{c=it},label={Text("Category")},singleLine=true)
            }},
            confirmButton={TextButton(enabled=(a.toIntOrNull()?:0)>0 && c.isNotBlank(),onClick={
                store.update(e.id,a.toInt(),c,n);TrackerEvents.saved();editExpense=null
            }){Text("Save changes",color=Mint)}},
            dismissButton={Row {
                TextButton(onClick={store.delete(e.id);TrackerEvents.saved();editExpense=null}){Text("Delete",color=Coral)}
                TextButton(onClick={editExpense=null}){Text("Cancel")}
            }},containerColor=Panel2)
    }
}
