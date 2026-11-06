package com.bob.ai

import android.app.Application
import com.bob.ai.di.AppContainer

class BobApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
