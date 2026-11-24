package com.paintbymoonlight.pictures

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A picture to paint that isn't the painter's own: one of the library's, bundled as
 * `assets/samples/<id>.jpg` (the iOS app's files byte for byte), or a night's from the museums
 * ([NightSchedule]), on the device once downloaded.
 */
data class Sample(
    val id: String,
    val title: String,
    val provenance: Provenance,
    /** A night's picture: its full picture (maybe not here yet) and its 640 px thumbnail. */
    val night: NightFiles? = null,
) {
    /** The creator shown under a painting's title: a painter is part of how people know a painting, a photographer rarely is. */
    val painter: String? get() = provenance.creator.takeIf { provenance.kind == SampleKind.Painting }

    /** Its title and creator, as TalkBack reads a tile. */
    val label: String get() = if (provenance.creator.isEmpty()) title else "$title, ${provenance.creator}"
}

data class NightFiles(val picture: File, val thumbnail: File)

enum class SampleKind { Painting, Photograph }

/** Who made a picture and the terms it ships under, shown verbatim as its record has them. */
data class Provenance(
    val kind: SampleKind,
    val creator: String,
    /** As the source dates the work: "1968", "c. 1830–32". */
    val year: String,
    /** The collection or agency the file comes from. */
    val credit: String,
    val license: String,
) {
    /** "Katsushika Hokusai, c. 1830–32". */
    val byline: String get() = listOf(creator, year).filter { it.isNotEmpty() }.joinToString(", ")
}

/**
 * The library: `assets/samples/library.json`, the iOS app's own file, its pictures in their
 * curated order with each one's provenance and license evidence (`docs/picture-library.md` in
 * the iOS repository has the rules; a picture is never added here, only taken as iOS ships it).
 */
class SampleLibrary(val all: List<Sample>) {
    /** One kind's pictures, in the library's order. */
    fun all(kind: SampleKind) = all.filter { it.provenance.kind == kind }

    fun named(id: String) = all.firstOrNull { it.id == id }

    /**
     * Tonight's Picture from the library: its paintings in turn, another each calendar day, the
     * same all day. As iOS counts it: whole days from the reference instant (2001-01-01 00:00
     * UTC, seen in [zone]) to the start of [date] in [zone], so every phone in one time zone
     * shows the iPhone's picture.
     */
    fun tonight(date: LocalDate, zone: ZoneId): Sample? {
        val paintings = all(SampleKind.Painting)
        if (paintings.isEmpty()) return null
        val n = paintings.size
        return paintings[(((dayNumber(date, zone) % n) + n) % n).toInt()]
    }

    /**
     * Tonight's Picture and the nights after it, as the gallery's shelf offers them: the
     * library's paintings in [tonight]'s turn from [date] on, those already held skipped, at
     * most [count], each once.
     */
    fun nights(date: LocalDate, zone: ZoneId, count: Int, held: Set<String>): List<Sample> {
        val paintings = all(SampleKind.Painting)
        val first = tonight(date, zone) ?: return emptyList()
        if (count <= 0) return emptyList()
        val start = paintings.indexOf(first)
        return (paintings.drop(start) + paintings.take(start)).filter { it.id !in held }.take(count)
    }

    companion object {
        private val REFERENCE = Instant.parse("2001-01-01T00:00:00Z")

        /** Foundation's day count: the wall clock's whole days, a part day not counted. */
        fun dayNumber(date: LocalDate, zone: ZoneId): Long =
            ChronoUnit.DAYS.between(REFERENCE.atZone(zone), date.atStartOfDay(zone))

        private val json = Json { ignoreUnknownKeys = true }

        /** The pictures a `library.json` lists, in its order; a record of a kind this app doesn't know is left out. */
        fun listed(text: String): List<Sample> = json.decodeFromString<List<Record>>(text).mapNotNull { r ->
            val kind = when (r.kind) {
                "painting" -> SampleKind.Painting
                "photograph" -> SampleKind.Photograph
                else -> return@mapNotNull null
            }
            Sample(r.id, r.title, Provenance(kind, r.creator, r.year, r.credit, r.license))
        }

        @Volatile private var loaded: SampleLibrary? = null

        /** The bundled library, read once. */
        fun of(context: Context): SampleLibrary = loaded ?: synchronized(this) {
            loaded ?: SampleLibrary(
                try {
                    context.assets.open("samples/library.json").bufferedReader().use { listed(it.readText()) }
                } catch (e: Exception) {
                    Log.e("Samples", "library.json didn't load", e)
                    emptyList()
                },
            ).also { loaded = it }
        }

        fun asset(id: String) = "samples/$id.jpg"
    }

    /** The fields of a record the app reads; the rest are for the people who audit the library. */
    @Serializable
    private class Record(
        val id: String,
        val kind: String,
        val title: String,
        val creator: String,
        val year: String,
        val credit: String,
        val license: String,
    )
}
