package com.paintbymoonlight.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A painting one painter sends another to paint, the `.moonlight` file the iOS app's Send
 * Template… writes and opens: its template exactly as it is, its title and settings, the paints'
 * names and, if the painter chose, the photo. Never its progress: the receiver paints a blank
 * page. The core reads and writes it (PaintCore's `SharedPainting`, the template in LZFSE as
 * iOS writes it), so a file goes either way between the two apps.
 */
class SharedPainting(
    /** The encoded template, checked by the core on the way in. */
    val template: ByteArray,
    /** Empty when the sender's was. */
    val title: String,
    /** The settings' JSON to record (PaintCore's `GenerationSettings`). */
    val settingsJson: String,
    /** The sender's paint names (seed and vocabulary), or null for a file without them. */
    val nicknameSeed: Long?,
    val nicknameVersion: Int?,
    /** The photo's encoded image, undecoded, when the sender included it. */
    val photo: ByteArray?,
) {
    companion object {
        /** The file's type: the iOS app declares none, so this one is the Android app's own. */
        const val MIME_TYPE = "application/vnd.paintbymoonlight.painting"
        const val EXTENSION = "moonlight"

        /** Reads a file someone sent, as hostile input; throws [CoreException] (3 damaged, 4 from a newer app). */
        suspend fun read(file: ByteArray): SharedPainting = withContext(Dispatchers.Default) {
            val parts = NativeCore.sharedRead(file)
            val info = json.decodeFromString<Info>(parts[1]!!.decodeToString())
            SharedPainting(parts[0]!!, info.title, info.settings, info.nicknameSeed, info.nicknameVersion, parts[2])
        }

        /** The file to send: [photo] only when the painter chose to include it. */
        suspend fun write(
            template: ByteArray, title: String, settingsJson: String, photo: ByteArray?, nicknameSeed: Long, nicknameVersion: Int,
        ): ByteArray = withContext(Dispatchers.Default) {
            NativeCore.sharedWrite(template, title.encodeToByteArray(), settingsJson.encodeToByteArray(), photo, nicknameSeed, nicknameVersion)
        }

        private val json = Json { ignoreUnknownKeys = true }
    }

    /** What the core reports beside the template and photo (`ReceivedInfo` in CABI.swift). */
    @Serializable
    private class Info(val title: String, val settings: String, val nicknameSeed: Long? = null, val nicknameVersion: Int? = null)
}
