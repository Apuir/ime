package com.ninthsoft.ime

import android.app.Application
import androidx.startup.AppInitializer
import com.ninthsoft.ime.engine.EngineInitializer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus


class ImeApplication : Application() {
    val coroutineScope = MainScope() + CoroutineName(javaClass.name)

    companion object {
        private var instance: ImeApplication? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("ime application is not created!")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppInitializer.getInstance(this).initializeComponent(EngineInitializer::class.java)
    }
}