package com.ninthsoft.ime.engine

import android.content.Context
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.speech.SherpaSpeechClient
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.base.util.ResourceExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import splitties.views.dsl.core.BuildConfig
import timber.log.Timber
import java.io.File

/**
 * 手动启动入口，取代 androidx.startup 的 Initializer 链。
 *
 * 调用 [initialize] 会按顺序完成：
 * 1. 初始化日志（Timber）
 * 2. 按需解压资源（resource.zip）
 * 3. 创建并切换到 [RimeEngine]
 *
 * 该过程是幂等的，可被多次调用，仅首次调用时实际执行。
 */
object AppStartup {
    private const val VERSION_FILE = "version.txt"
    private const val RESOURCE_ASSET = "resource.zip"

    @Volatile
    private var initialized = false
    private val lock = Any()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val app = context.applicationContext as? ImeApplication
                ?: throw IllegalStateException("ImeApplication is not created!")
            setupLogger()
            extractResourcesIfNeeded(context, app)
            EngineFactory.switchTo(context, RimeEngine::class)
            setupSound(context)
            SherpaSpeechClient.preStartSync(context)
            initialized = true
        }
    }

    private fun setupLogger() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun setupSound(context: Context) {
        InputFeedbacks.initSoundPool(context)
    }

    private fun extractResourcesIfNeeded(context: Context, app: ImeApplication) {
        val destDir = context.getExternalFilesDir(null) ?: context.filesDir
        if (File(destDir, VERSION_FILE).exists()) return
        app.notifyState(ImeApplication.InitState.EXTRACTING)
        runBlocking(Dispatchers.IO) {
            ResourceExtractor.extract(context, RESOURCE_ASSET, destDir)
            Thread.sleep(1000)
        }
    }
}
