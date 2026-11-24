package com.paintbymoonlight.museum

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream
import java.text.Normalizer

/**
 * The Museum's catalog (debug builds only, [com.paintbymoonlight.Unreleased], as iOS keeps it
 * behind `#if UNRELEASED`; painting-company row 369): the open-access paintings, prints and
 * drawings of museums that publish them as public domain (CC0: the Art Institute of Chicago,
 * the Cleveland Museum of Art, the National Gallery of Art), each with the movement it belongs
 * to and how colorful it is.
 *
 * It is the iOS app's file, one JSON that its `tools/museum/catalog.py` writes and the `museum`
 * branch of its repository serves ([SOURCE]): `{"version": 1, "made", "museums": {code: name},
 * "movements": [name, …], "works": [Work, …]}`, the movements in the order the browser lists
 * them. The pictures come from the museums' own servers ([MuseumImages]).
 */
class MuseumCatalog(
    val version: Int,
    /** The day the tool wrote it ("2026-11-20"). */
    val made: String,
    val museums: Map<String, String>,
    val movements: List<String>,
    val works: List<Work>,
) {
    /** Each work's title, artist, movement and year as search words, folded as a query's are, each word after a space. */
    internal val searchText: List<String> = works.map { w ->
        words("${w.title} ${w.artist} ${w.movement} ${w.year}").joinToString("") { " $it" }
    }

    /** Painting names that more than one work has: a painting so named can't tell which it was painted from. */
    val sharedNames: Set<String> = works.groupingBy { MuseumPainted.key(it.paintingTitle) }.eachCount().filterValues { it > 1 }.keys

    private val byId = works.associateBy { it.id }

    fun work(id: String) = byId[id]

    /** One artwork, as the museum describes it: `title`, `artist`, `year` and `medium` are its words, shown verbatim. */
    @Serializable
    data class Work(
        val id: String,
        val title: String,
        val artist: String,
        /** As the museum dates the work: "1891", "c. 1830–32". */
        val year: String = "",
        /** The year the work was finished, for sorting; null when the museum gives none. */
        val date: Int? = null,
        val movement: String,
        val kind: String,
        /** Hasler and Süsstrunk's colorfulness: about 0 for grey, 30 to 60 for most paintings, over 80 for the loudest. */
        val colorfulness: Double = 0.0,
        /** The image's mean color (`rrggbb`), the tile's ground while its picture loads. */
        val average: String = "",
        val width: Int = 0,
        val height: Int = 0,
        /** The key of [museums] that holds it. */
        val museum: String = "",
        /** A rendition about 400 to 900 px wide, for the browser's tiles. */
        val thumb: String,
        /** The largest rendition the app paints from (1,686 to 3,400 px across, as the museum serves it). */
        val image: String,
        /** The work's page on the museum's website. */
        val page: String? = null,
        val medium: String = "",
    ) {
        val isPainting get() = kind == "painting"

        /** Width over height, 1 when the museum gives no size. */
        val aspectRatio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f

        /** The work's title as a painting's name: without a series it belongs to, and at most about 60 characters. */
        val paintingTitle: String
            get() {
                var t = title
                for (cut in listOf(", from the series", " from the series", ", from the ")) {
                    val at = t.indexOf(cut, ignoreCase = true)
                    if (at >= 0) t = t.substring(0, at)
                }
                t = t.trim { it.isWhitespace() || isPunctuation(it) }
                if (t.length <= 60) return t.ifEmpty { title }
                val short = t.substring(0, 60)
                val end = short.lastIndexOf(' ').takeIf { it >= 0 } ?: short.length
                return short.substring(0, end) + "…"
            }
    }

    companion object {
        const val SOURCE = "https://raw.githubusercontent.com/pbordjadze/paint-by-number/refs/heads/museum/museum.json"
        val KINDS = setOf("painting", "print", "drawing")

        private val json = Json { ignoreUnknownKeys = true }

        @Serializable
        private class File(
            val version: Int,
            val made: String = "",
            val museums: Map<String, String>,
            val movements: List<String>,
            val works: List<Work>,
        )

        /** The museums whose servers the pictures may come from, their subdomains included. */
        val HOSTS = setOf("artic.edu", "clevelandart.org", "nga.gov")
        private val ID = Regex("[a-z]+-[A-Za-z0-9]+([._-][A-Za-z0-9]+)*")

        /**
         * A catalog from its JSON, read as hostile input: a work this build can't use (a kind it
         * doesn't know, an id not of the catalog's pattern, a picture not served over HTTPS by
         * one of [hosts]) is left out.
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(input: InputStream, hosts: Set<String> = HOSTS): MuseumCatalog {
            val file = json.decodeFromStream<File>(input)
            val works = file.works.filter {
                it.kind in KINDS && it.id.length <= 64 && ID.matches(it.id) && served(it.thumb, hosts) && served(it.image, hosts)
            }
            return MuseumCatalog(file.version, file.made, file.museums, file.movements, works)
        }

        fun decode(text: String, hosts: Set<String> = HOSTS) = decode(text.byteInputStream(), hosts)

        /** An HTTPS address on one of [hosts] or a subdomain of one. */
        internal fun served(url: String, hosts: Set<String>): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && hosts.any { host == it || host.endsWith(".$it") }
        }

        /** A text's words, case, diacritics and width folded, split at whatever isn't a letter or a number. */
        fun words(text: String): List<String> = fold(text).split { !it.isLetterOrDigit() }

        internal fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(java.util.Locale.ROOT)

        private val MARKS = Regex("\\p{M}+")

        private fun isPunctuation(c: Char) = when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }

        private inline fun String.split(isSeparator: (Char) -> Boolean): List<String> {
            val out = ArrayList<String>()
            var start = -1
            for (i in indices) {
                if (isSeparator(this[i])) {
                    if (start >= 0) out.add(substring(start, i))
                    start = -1
                } else if (start < 0) {
                    start = i
                }
            }
            if (start >= 0) out.add(substring(start))
            return out
        }
    }
}

/** What the browser shows of the catalog: a movement (or all), a kind, a search and an order. A pure function of the catalog. */
data class MuseumQuery(
    val movement: String? = null,
    val kinds: Kinds = Kinds.Everything,
    val order: Order = Order.Colorful,
    val search: String = "",
    /** The order's seed while shuffled: the same seed, the same order. */
    val seed: Long = 1,
) {
    enum class Order(val label: String) { Colorful("Most Colorful"), Oldest("Oldest First"), Newest("Newest First"), Shuffled("Shuffle") }

    enum class Kinds(val label: String) {
        Everything("Everything"), Paintings("Paintings"), PrintsAndDrawings("Prints and Drawings");

        fun admits(work: MuseumCatalog.Work) = when (this) {
            Everything -> true
            Paintings -> work.isPainting
            PrintsAndDrawings -> !work.isPainting
        }
    }

    /**
     * The works the browser shows, in its order, as iOS shows them. A search needs every word to
     * start a word of the title, the artist, the movement or the year, ignoring case and
     * diacritics. Most Colorful over all movements takes each movement's most colorful in turn.
     */
    fun works(catalog: MuseumCatalog): List<MuseumCatalog.Work> {
        val words = MuseumCatalog.words(search).map { " $it" }
        val kept = ArrayList<MuseumCatalog.Work>()
        catalog.works.forEachIndexed { index, work ->
            if (movement != null && work.movement != movement) return@forEachIndexed
            if (!kinds.admits(work)) return@forEachIndexed
            if (words.isNotEmpty()) {
                val haystack = catalog.searchText[index]
                if (!words.all { haystack.contains(it) }) return@forEachIndexed
            }
            kept.add(work)
        }
        return when (order) {
            Order.Colorful -> {
                val sorted = kept.sortedWith(compareByDescending<MuseumCatalog.Work> { it.colorfulness }.thenBy { it.id })
                if (movement != null) return sorted
                // Every movement's most colorful before any movement's second: the most colorful
                // of all are one tradition's, and a wall of it hid the rest of the museum.
                val seen = HashMap<String, Int>()
                val ranks = sorted.map { w -> (seen[w.movement] ?: 0).plus(1).also { seen[w.movement] = it } }
                sorted.indices.sortedWith(compareBy<Int> { ranks[it] }.thenBy { it }).map { sorted[it] }
            }
            Order.Oldest -> kept.sortedWith(compareBy<MuseumCatalog.Work> { it.date ?: Int.MAX_VALUE }.thenBy { it.id })
            Order.Newest -> kept.sortedWith(compareByDescending<MuseumCatalog.Work> { it.date ?: Int.MIN_VALUE }.thenBy { it.id })
            Order.Shuffled -> kept.sortedWith(compareBy<MuseumCatalog.Work> { mix(it.id, seed.toULong()) }.thenBy { it.id })
        }
    }

    /** How many works each movement holds under this query's kind (whether its chip shows). */
    fun counts(catalog: MuseumCatalog): Map<String, Int> =
        catalog.works.filter { kinds.admits(it) }.groupingBy { it.movement }.eachCount()

    companion object {
        /** FNV-1a over the id's bytes, then SplitMix64's finalizer with the seed: iOS's shuffle exactly. */
        fun mix(id: String, seed: ULong): ULong {
            var hash = 0xcbf29ce484222325uL
            for (byte in id.encodeToByteArray()) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
            var z = hash + seed * 0x9e3779b97f4a7c15uL
            z = (z xor (z shr 30)) * 0xbf58476d1ce4e5b9uL
            z = (z xor (z shr 27)) * 0x94d049bb133111ebuL
            return z xor (z shr 31)
        }
    }
}

/**
 * Which works the painter has painted: a finished one hangs in a frame on the wall, and one
 * under way says Resume on its page. A painting from the Museum is named after its work
 * ([MuseumCatalog.Work.paintingTitle]) and Paint This records the work's id; a work matches a
 * painting that bears its name when its id was recorded or no other work has that name.
 */
object MuseumPainted {
    /** A name as compared: case, diacritics and width folded, ends trimmed. */
    fun key(name: String) = MuseumCatalog.fold(name).trim()

    fun matches(work: MuseumCatalog.Work, names: Set<String>, recorded: Set<String>, shared: Set<String>): Boolean {
        val name = key(work.paintingTitle)
        if (name !in names) return false
        return work.id in recorded || name !in shared
    }
}
