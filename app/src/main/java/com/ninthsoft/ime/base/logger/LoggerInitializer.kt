package com.ninthsoft.ime.base.logger

import android.content.Context
import androidx.startup.Initializer
import splitties.views.dsl.core.BuildConfig
import timber.log.Timber

class LoggerInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}