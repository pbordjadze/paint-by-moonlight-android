package com.paintbymoonlight

import android.app.Application
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.Preferences
import com.paintbymoonlight.outside.OutsideDesk

class MoonlightApp : Application() {
    lateinit var library: Library
        private set
    lateinit var preferences: Preferences
        private set

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        preferences = Preferences(this)
        OutsideDesk.start(this, library)
    }
}
