package com.paintbymoonlight

import android.app.Application
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.Preferences
import com.paintbymoonlight.outside.OutsideDesk
import com.paintbymoonlight.unlock.UnlimitedPaintings

class MoonlightApp : Application() {
    lateinit var library: Library
        private set
    lateinit var preferences: Preferences
        private set
    lateinit var paintings: UnlimitedPaintings
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        preferences = Preferences(this)
        paintings = UnlimitedPaintings.forLaunch(this)
        OutsideDesk.start(this, library)
    }
}
