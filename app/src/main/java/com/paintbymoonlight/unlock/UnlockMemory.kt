package com.paintbymoonlight.unlock

import android.content.Context
import java.time.Instant

/** What the device remembers between launches: the free paintings' count and whether the unlock was owned when Play last answered. */
interface UnlockMemory {
    var free: FreePaintings
    var owned: Boolean

    companion object {
        /** In the app's own preferences, which back up with the paintings. */
        fun of(context: Context): UnlockMemory = PreferencesMemory(context)

        fun inMemory(free: FreePaintings = FreePaintings(), owned: Boolean = false): UnlockMemory =
            object : UnlockMemory {
                override var free = free
                override var owned = owned
            }
    }
}

private class PreferencesMemory(context: Context) : UnlockMemory {
    private val prefs = context.getSharedPreferences("unlock", Context.MODE_PRIVATE)

    override var free: FreePaintings
        get() = FreePaintings(
            prefs.getInt(LEFT, FreePaintings.MOST),
            if (prefs.contains(SINCE)) Instant.ofEpochMilli(prefs.getLong(SINCE, 0)) else null,
        )
        set(value) {
            prefs.edit().putInt(LEFT, value.left).apply {
                val since = value.since
                if (since != null) putLong(SINCE, since.toEpochMilli()) else remove(SINCE)
            }.apply()
        }

    override var owned: Boolean
        get() = prefs.getBoolean(OWNED, false)
        set(value) { prefs.edit().putBoolean(OWNED, value).apply() }

    private companion object {
        const val LEFT = "freePaintings.left"
        const val SINCE = "freePaintings.since"
        const val OWNED = "unlimitedPaintings.owned"
    }
}
