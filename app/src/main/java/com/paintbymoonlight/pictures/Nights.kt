package com.paintbymoonlight.pictures

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Tonight's Picture from the museums (debug builds only, [com.paintbymoonlight.Unreleased], as
 * iOS keeps it behind `#if UNRELEASED`; painting-company row 368): one public-domain picture a
 * night, the same for every painter on that calendar date, on iPhone and Android alike.
 *
 * The schedule is the iOS app repository's `nights` branch, `nights.json` and the pictures
 * beside it (a 2048 px picture and a 640 px thumbnail each), which a workflow there writes each
 * day. [refresh] downloads it, every thumbnail and tonight's and tomorrow's pictures into the
 * app's files (Nights/); everything else reads what is on disk, so a painter offline keeps
 * what came before, and with nothing at all Tonight's Picture is the library's turn
 * ([SampleLibrary.tonight]). The schedule is read as hostile input ([decode]), and a full
 * picture is kept only when its checksum is the schedule's.
 *
 * iOS's ballots (the painters who finished a night vote on a coming one) aren't offered here yet.
 */
class Nights(private val folder: File) {
    @Serializable
    data class Schedule(
        val format: Int,
        val pictures: Map<String, Picture>,
        val nights: List<Night>,
        val ballots: List<Ballot> = emptyList(),
    )

    @Serializable
    data class Picture(
        val id: String,
        val title: String,
        val creator: String,
        val year: String,
        val credit: String,
        val license: String,
        val source: String,
        val medium: String? = null,
        val place: String? = null,
        val image: String,
        val thumb: String,
        val sha256: String,
    )

    /** `date` is a calendar date, "2026-11-22", the same everywhere. */
    @Serializable
    data class Night(val date: String, val picture: String)

    @Serializable
    data class Ballot(val night: String, val opens: String, val closes: String, val choices: List<String>)

    data class EarlierNight(val day: String, val sample: Sample)

    private val current = MutableStateFlow(
        runCatching { decode(File(folder, SCHEDULE).readText()) }.getOrNull(),
    )
    private val _revision = MutableStateFlow(0)
    /** Moves when the schedule or its files change, so what shows them draws again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val refreshing = Mutex()
    @Volatile private var asked = 0L

    val schedule: Schedule? get() = current.value

    /** The picture [id] names, once its thumbnail is here (its full picture may still be on its way). */
    fun sample(id: String): Sample? {
        val picture = schedule?.pictures?.get(id) ?: return null
        val thumb = File(folder, picture.thumb)
        if (!thumb.exists()) return null
        return Sample(
            id = picture.id,
            title = picture.title,
            provenance = Provenance(SampleKind.Painting, picture.creator, picture.year, picture.credit, picture.license),
            night = NightFiles(File(folder, picture.image), thumb),
        )
    }

    /** The night's picture on [date], when the schedule has one and its thumbnail is here. */
    fun night(date: LocalDate): Sample? {
        val day = date.toString()
        return schedule?.nights?.lastOrNull { it.date == day }?.let { sample(it.picture) }
    }

    /** The schedule's nights before [date], newest first, with their pictures here. */
    fun earlierNights(date: LocalDate): List<EarlierNight> {
        val today = date.toString()
        val seen = HashSet<String>()
        return schedule?.nights.orEmpty().filter { it.date < today }.sortedByDescending { it.date }.mapNotNull { night ->
            if (!seen.add(night.date)) return@mapNotNull null
            sample(night.picture)?.let { EarlierNight(night.date, it) }
        }
    }

    /**
     * Downloads the schedule, the thumbnails it names and [today]'s and the next day's pictures,
     * then lets go of files it no longer names. Quiet on failure: what is on disk stays. Asked
     * again within ten minutes, it asks nothing.
     */
    suspend fun refresh(today: LocalDate = LocalDate.now()) {
        val now = System.currentTimeMillis()
        if (now - asked < 10 * 60_000L) return
        refreshing.withLock {
            if (now - asked < 10 * 60_000L) return
            asked = now
            try {
                val file = File(folder, SCHEDULE)
                val fresh = File(folder, "$SCHEDULE.new")
                Net.download(BASE + SCHEDULE, fresh, limit = MAX_SCHEDULE)
                val schedule = withContext(Dispatchers.IO) { decode(fresh.readText()) }
                if (schedule == null) {
                    fresh.delete()
                    return
                }
                withContext(Dispatchers.IO) { if (!fresh.renameTo(file)) throw IOException("couldn't keep the schedule") }
                current.value = schedule
                _revision.value++
                for (picture in schedule.pictures.values.sortedBy { it.id }) {
                    runCatching { fetch(picture.thumb, null) }
                }
                _revision.value++
                val soon = setOf(today.toString(), today.plusDays(1).toString())
                for (night in schedule.nights.filter { it.date in soon }) {
                    schedule.pictures[night.picture]?.let { runCatching { fetch(it.image, it.sha256) } }
                }
                withContext(Dispatchers.IO) { prune(schedule) }
                _revision.value++
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.i(TAG, "the nights' schedule didn't come: $e")
            }
        }
    }

    /** The full picture of [id], downloaded now if it isn't here yet (an earlier night's). */
    suspend fun fullPicture(id: String): File {
        val picture = schedule?.pictures?.get(id) ?: throw IOException("no night's picture $id")
        return fetch(picture.image, picture.sha256)
    }

    private suspend fun fetch(path: String, sha256: String?): File {
        val target = File(folder, path)
        if (target.exists()) return target
        val incoming = File(folder, "$path.new")
        Net.download(BASE + path, incoming)
        withContext(Dispatchers.IO) {
            if (sha256 != null && digest(incoming) != sha256) {
                incoming.delete()
                throw IOException("$path isn't the schedule's picture")
            }
            if (!incoming.renameTo(target)) throw IOException("couldn't keep $path")
        }
        return target
    }

    private fun prune(schedule: Schedule) {
        val kept = schedule.pictures.values.flatMap { listOf(it.image, it.thumb) }.toSet()
        for (name in listOf("pictures", "thumbs")) {
            File(folder, name).listFiles()?.forEach { if ("$name/${it.name}" !in kept) it.delete() }
        }
    }

    companion object {
        private const val TAG = "Nights"
        private const val SCHEDULE = "nights.json"
        private const val MAX_SCHEDULE = 4L shl 20
        /** Where the iOS repository's Nights workflow publishes. */
        const val BASE = "https://raw.githubusercontent.com/pbordjadze/paint-by-number/nights/"

        private val json = Json { ignoreUnknownKeys = true }
        private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")

        /** Background work that outlives a screen: a refresh, the shelf's prefetch. */
        internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile private var shared: Nights? = null

        fun of(context: Context): Nights = shared ?: synchronized(this) {
            shared ?: Nights(File(context.applicationContext.filesDir, "Nights")).also { shared = it }
        }

        /** Starts a refresh that outlives the screen that asked for it. */
        fun refreshSoon(context: Context) {
            val nights = of(context)
            scope.launch { nights.refresh() }
        }

        /**
         * A schedule, or null when it isn't one this app can trust, exactly as iOS judges it: an
         * unknown format, a picture whose id or files aren't the pattern the workflow writes, a
         * night or ballot naming a picture it doesn't hold; at most 1000 pictures.
         */
        fun decode(text: String): Schedule? {
            if (text.length >= MAX_SCHEDULE) return null
            val schedule = try {
                json.decodeFromString<Schedule>(text)
            } catch (_: Exception) {
                return null
            }
            if (schedule.format != 1 || schedule.pictures.size > 1000) return null
            for ((id, p) in schedule.pictures) {
                if (id != p.id || !ID.matches(id) || p.image != "pictures/$id.jpg" || p.thumb != "thumbs/$id.jpg" ||
                    p.sha256.length != 64 || p.title.isEmpty() || p.title.length > 200 || p.creator.length > 200
                ) return null
            }
            if (!schedule.nights.all { DAY.matches(it.date) && it.picture in schedule.pictures }) return null
            if (!schedule.ballots.all { b -> DAY.matches(b.night) && b.choices.size == 3 && b.choices.all { it in schedule.pictures } }) return null
            return schedule
        }

        private fun digest(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    md.update(buffer, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
