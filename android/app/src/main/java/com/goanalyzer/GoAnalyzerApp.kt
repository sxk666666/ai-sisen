package com.goanalyzer

import android.app.Application
import com.goanalyzer.data.GoAnalyzerContainer

class GoAnalyzerApp : Application() {
    lateinit var container: GoAnalyzerContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = GoAnalyzerContainer(this)
        container.autoConnectIfSaved()
    }
}
