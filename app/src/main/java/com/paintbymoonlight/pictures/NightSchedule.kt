package com.paintbymoonlight.pictures

import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

/**
 * Tonight's Picture from the museums' nightly schedule, as the screens see it. Only debug builds
 * have one ([com.paintbymoonlight.Unreleased.nights]; the schedule itself is `Nights`, in
 * src/debug), as iOS keeps it behind `#if UNRELEASED`.
 */
interface NightSchedule {
    /** Moves when the schedule or its files change, so what shows them draws again. */
    val revision: StateFlow<Int>

    /** The night's picture on [date], when the schedule has one and its thumbnail is here. */
    fun night(date: LocalDate): Sample?

    /** The schedule's nights before [date], newest first, with their pictures here. */
    fun earlierNights(date: LocalDate): List<EarlierNight>

    /** The picture [id] names, once its thumbnail is here. */
    fun sample(id: String): Sample?

    /** Every picture of the schedule on this device, for the credits. */
    fun samples(): List<Sample>

    /** The full picture of [id], downloaded now if it isn't here yet. */
    suspend fun fullPicture(id: String): File

    /** Asks for the schedule in the background (at most every ten minutes). */
    fun refreshSoon()
}

data class EarlierNight(val day: String, val sample: Sample)
