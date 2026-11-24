package com.paintbymoonlight.ui.source

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paintbymoonlight.pictures.NightSchedule
import java.time.LocalDate

/** Today's date, read again each time the screen comes back, so a page left open past midnight shows the new night's picture. */
@Composable
internal fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        onPauseOrDispose {}
    }
    return today
}

/** The night schedule's revision, so what shows it draws again when it changes; 0 in a build without one. */
@Composable
internal fun nightsRevision(nights: NightSchedule?): Int = nights?.revision?.collectAsStateWithLifecycle()?.value ?: 0
