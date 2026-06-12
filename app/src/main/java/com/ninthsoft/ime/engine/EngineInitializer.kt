package com.ninthsoft.ime.engine

import android.content.Context
import androidx.startup.Initializer
import com.ninthsoft.ime.base.logger.LoggerInitializer

class EngineInitializer : Initializer<IEngine> {

    override fun create(context: Context): IEngine {
        return EngineFactory.switchTo(context = context, clazz = RimeEngine::class)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(LoggerInitializer::class.java)
    }
}