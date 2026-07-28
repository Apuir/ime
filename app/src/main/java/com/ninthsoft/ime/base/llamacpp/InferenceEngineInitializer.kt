package com.ninthsoft.ime.base.llamacpp

import android.content.Context
import androidx.startup.Initializer
import com.ninthsoft.ime.base.logger.LoggerInitializer
import timber.log.Timber
import java.io.File

class InferenceEngineInitializer : Initializer<InferenceEngine> {

    override fun create(context: Context): InferenceEngine {
        File(context.getExternalFilesDir(null), MODELS_DIR).also { it.mkdirs() }
        val engine = AiChat.getInferenceEngine(context)
        _instance = engine
        Timber.i("llama.cpp InferenceEngine created")
        return engine
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(LoggerInitializer::class.java)
    }

    companion object {
        const val MODELS_DIR = "model"

        @Volatile
        private var _instance: InferenceEngine? = null

        val instance: InferenceEngine
            get() = _instance ?: throw IllegalStateException(
                "InferenceEngine not initialized! Call InferenceEngineInitializer first."
            )
    }
}
