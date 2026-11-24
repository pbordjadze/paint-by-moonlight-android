package com.paintbymoonlight

import android.app.Application
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.Preferences

class MoonlightApp : Application() {
    lateinit var library: Library
        private set
    lateinit var preferences: Preferences
        private set

    override fun onCreate() {
        super.onCreate()
        Core.attach(this)
        library = Library(this)
        preferences = Preferences(this)
    }
}
