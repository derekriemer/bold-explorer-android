package com.boldexplorer

import android.app.Application
import com.boldexplorer.audio.OutputRouter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BoldExplorerApp : Application() {
    @Inject
    lateinit var outputRouter: OutputRouter

    override fun onCreate() {
        super.onCreate()
        outputRouter.start()
    }
}
