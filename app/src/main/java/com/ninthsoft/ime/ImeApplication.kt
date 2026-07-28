package com.ninthsoft.ime

import android.app.Application
import androidx.startup.AppInitializer
import com.ninthsoft.ime.base.llamacpp.ChatService
import com.ninthsoft.ime.base.llamacpp.InferenceEngineInitializer
import com.ninthsoft.ime.engine.EngineInitializer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import timber.log.Timber
import java.io.File


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