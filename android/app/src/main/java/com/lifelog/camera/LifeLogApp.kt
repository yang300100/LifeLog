package com.lifelog.camera

import android.app.Application
import com.lifelog.camera.util.CrashLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LifeLogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
    }
}
