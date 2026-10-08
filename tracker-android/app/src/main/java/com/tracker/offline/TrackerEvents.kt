package com.tracker.offline

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

object TrackerEvents {
    val revision = mutableIntStateOf(0)
    val voiceStatus = mutableStateOf("Starting offline listener…")
    val heard = mutableStateOf("Say Hey Tracker, then your expense")
    fun saved() { revision.intValue += 1 }
}
