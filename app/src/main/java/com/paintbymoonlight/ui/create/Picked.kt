package com.paintbymoonlight.ui.create

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The picture a new painting is made from, as the create screen is handed it (and keeps it across process death). */
@Serializable
sealed interface Picked {
    /** The painter's own photo, from the photo picker or the camera. */
    @Serializable
    @SerialName("photo")
    data class Photo(val uri: String) : Picked

    /** A picture of the library, or a night's from the museums, by its id; the painting records it. */
    @Serializable
    @SerialName("sample")
    data class Sample(val id: String) : Picked

    /** A work of the Museum: its full image's address and the painting's name after it. */
    @Serializable
    @SerialName("museum")
    data class Museum(val work: String, val title: String, val image: String) : Picked

    companion object {
        private val json = Json

        val Saver: Saver<Picked?, String> = Saver(
            save = { it?.let { picked -> json.encodeToString(serializer(), picked) } },
            restore = { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() },
        )
    }
}
