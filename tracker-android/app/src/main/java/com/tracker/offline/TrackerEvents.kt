package com.tracker.offline

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

object TrackerEvents {
    val revision = mutableIntStateOf(0)
    val voiceStatus = mutableStateOf("Starting offline listener…")
    val heard = mutableStateOf("Say Hey Tracker, then an expense")
    val wakePreview = mutableStateOf("No wake phrase yet")
    val wakeEvents = mutableIntStateOf(0)
    val micLevel = mutableIntStateOf(0)
    val micActive = mutableStateOf(false)
    val lastAudioMs = mutableLongStateOf(0L)
    val error = mutableStateOf("")
    fun saved() { revision.intValue += 1 }
}
