package com.ninthsoft.ime.base.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.k2fsa.sherpa.onnx.*
import com.ninthsoft.ime.input.ImeInputMethodService
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.java
import kotlin.math.abs

object SherpaSpeechClient {
    private val recognizerRef = AtomicReference<OfflineRecognizer?>(null)

    // 采用双重引用或直接维持标准 AtomicReference
    private val currentStreamRef = AtomicReference<OfflineStream?>(null)
    private val punctuationRef = AtomicReference<OfflinePunctuation?>(null)

    // 使用 AtomicBoolean 确保多线程状态切换的可见性与原子性
    private val isHolding = AtomicBoolean(false)

    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private var serviceRef: WeakReference<ImeInputMethodService>? = null
    private val initLock = Any()  // 初始化锁
    private val audioLock = Any() // 音频处理与流释放锁

    private const val SAMPLE_RATE = 16000

    // 静音检测阈值（振幅比值 0.0f ~ 1.0f）
    private const val NOISE_THRESHOLD = 0.02f

    /**
     * 按需懒加载初始化推理引擎（运行在 IO 线程）
     */
    private fun initEngineIfNeeded(ctx: Context): Boolean {
        if (recognizerRef.get() != null) return true

        synchronized(initLock) {
            if (recognizerRef.get() != null) return true

            val appContext = ctx.applicationContext
            val voiceDir = File(appContext.getExternalFilesDir(null), "voice").also { it.mkdirs() }
            val metaFile = File(voiceDir, "metadata.json")
            val tokensFile = File(voiceDir, "tokens.txt")

            var numThreads = 4
            var modelName = "model.int8.onnx"
            var punctModelName = "punct.model.int8.onnx"
            var language = "auto"
            var provider = "cpu"
            var decodingMethod = "greedy_search"

            if (metaFile.exists()) {
                try {
                    val jsonString = metaFile.readText(Charsets.UTF_8)
                    val json = JSONObject(jsonString)
                    numThreads = json.optInt("numThreads", numThreads)
                    modelName = json.optString("model", modelName)
                    punctModelName = json.optString("punctModel", punctModelName)
                    language = json.optString("language", language)
                    provider = json.optString("provider", provider)
                    decodingMethod = json.optString("decodingMethod", decodingMethod)
                    Timber.i("⚙️ 成功从外部 JSON 载入配置")
                } catch (e: Exception) {
                    Timber.e(e, "⚠️ 外部 metadata.json 解析失败")
                }
            }

            val modelFile = File(voiceDir, modelName)
            if (!modelFile.exists() || !tokensFile.exists()) {
                Timber.e("❌ 初始化终止：未找到指定的模型文件或词表。")
                return false
            }
            val punctModelFile = File(voiceDir, punctModelName)
            return try {
                // 1. 初始化语音识别 ASR 引擎
                val senseVoiceConfig = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = language,
                    useInverseTextNormalization = true,
                    qnnConfig = QnnConfig()
                )

                val modelConfig = OfflineModelConfig(
                    tokens = tokensFile.absolutePath,
                    senseVoice = senseVoiceConfig,
                    debug = false,
                    numThreads = numThreads,
                    provider = provider,
                )

                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = decodingMethod,
                )

                recognizerRef.set(OfflineRecognizer(null, config))
                Timber.i("🚀 Sherpa-onnx 外置离线 ASR 引擎初始化成功！")

                // 2. 初始化标点模型
                if (punctModelFile.exists()) {
                    try {
                        val punctConfig = OfflinePunctuationConfig(
                            model = OfflinePunctuationModelConfig(
                                ctTransformer = punctModelFile.absolutePath,
                                numThreads = numThreads,
                                debug = false,
                                provider = provider
                            )
                        )
                        punctuationRef.set(OfflinePunctuation(null, punctConfig))
                        Timber.i("🎯 标点模型加载成功: ${punctModelFile.name}")
                    } catch (e: Throwable) {
                        Timber.e(e, "⚠️ 标点模型加载失败，将输出无标点文本")
                        punctuationRef.set(null)
                    }
                } else {
                    Timber.w("⚠️ 未能在 voice 目录下找到标点模型，将输出无标点纯文本")
                    punctuationRef.set(null)
                }

                true
            } catch (e: Throwable) {
                Timber.e(e, "❌ 外置引擎模型初始化崩溃")
                false
            }
        }
    }

    /**
     * 按下语音键，启动会话
     */
    fun startHoldSession(service: ImeInputMethodService) {
        if (!isHolding.compareAndSet(false, true)) return

        serviceRef = WeakReference(service)

        service.scope?.launch {
            val initSuccess = withContext(Dispatchers.IO) { initEngineIfNeeded(service) }

            // 用户在引擎初始化期间可能已松手；放弃本次录音，避免“松开后才打开麦克风”的竞态。
            if (!isHolding.get()) {
                clearReferences()
                return@launch
            }

            if (initSuccess) {
                val engine = recognizerRef.get()
                if (engine != null) {
                    try {
                        synchronized(audioLock) {
                            val newStream = engine.createStream()
                            currentStreamRef.set(newStream)
                        }
                        startAudioStreaming(service)
                    } catch (t: Throwable) {
                        Timber.e(t, "创建推理流失败")
                        cancelSession()
                    }
                }
            } else {
                toast(service, "语音识别本地组件未就绪")
                cancelSession()
            }
        }
    }

    /**
     * 松开语音键，结束当前会话并上屏。
     * 仅置位 isHolding=false 让录音循环优雅退出，复用协程内部的 [withContext(NonCancellable)]
     * 来确保“最后一帧全量解码 + setComposingText + onDone”不会被取消抢断。
     */
    fun stopHoldSession() {
        if (!isHolding.compareAndSet(true, false)) return
        val job = audioJob
        audioJob = null
        val svc = serviceRef?.get()
        if (svc != null) {
            svc.scope?.launch {
                // 等待内部优雅收尾（最终解码 + 上屏 + onDone 全部在 NonCancellable 内完成）
                job?.join()
                withContext(NonCancellable) {
                    svc.currentInputConnection?.finishComposingText()
                    clearReferences()
                }
            }
        } else {
            // 没有 service 可用，强制取消并清理
            job?.cancel()
            clearReferences()
            runCatching { SpeechUiBridge.onDone?.invoke() }
        }
    }

    /**
     * 核心音频采集与流式投喂控制
     */
    private fun startAudioStreaming(service: ImeInputMethodService) {
        if (ContextCompat.checkSelfPermission(
                service, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(service, SpeechPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { service.startActivity(intent) }
            runCatching { SpeechUiBridge.onDone?.invoke() }
            resetStateDirectly()
            return
        }

        audioJob = service.scope?.launch(Dispatchers.IO) {
            var rec: AudioRecord? = null
            try {
                val ch = AudioFormat.CHANNEL_IN_MONO
                val fmt = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, ch, fmt)

                val chunkBytes = (SAMPLE_RATE * 80 / 1000) * 2
                val bufSize = minBuf.coerceAtLeast(chunkBytes * 2)

                rec = listOf(
                    MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION
                ).firstNotNullOfOrNull { source ->
                    runCatching {
                        AudioRecord(source, SAMPLE_RATE, ch, fmt, bufSize)
                    }.getOrNull()?.takeIf {
                        it.state == AudioRecord.STATE_INITIALIZED
                    }
                }
                if (rec == null) {
                    Timber.e("❌ 无法创建 AudioRecord 实例")
                    return@launch
                }

                audioRecord = rec
                rec.startRecording()

                val chunk = ByteArray(chunkBytes)
                var notifiedRecordingStarted = false
                var loopCounter = 0

                var hasRealAudioEntered = false
                var continuousSilenceCount = 0
                val maxTailBufferFrames = 10

                while (isActive && isHolding.get() && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val n = try {
                        rec.read(chunk, 0, chunk.size)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (n < 0) break
                    if (n == 0) {
                        delay(10)
                        continue
                    }

                    if (!notifiedRecordingStarted) {
                        notifiedRecordingStarted = true
                        withContext(Dispatchers.Main) {
                            runCatching { SpeechUiBridge.onRecordingStarted?.invoke() }
                        }
                    }

                    val sampleCount = n / 2
                    val shortChunk = ShortArray(sampleCount)
                    ByteBuffer.wrap(chunk, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .get(shortChunk)

                    val amp = calculateAmplitude(shortChunk, sampleCount)
                    val floatChunk = FloatArray(sampleCount)

                    val isCurrentFrameSpeech = amp >= NOISE_THRESHOLD

                    if (isCurrentFrameSpeech) {
                        continuousSilenceCount = 0
                        hasRealAudioEntered = true
                    } else {
                        continuousSilenceCount++
                    }

                    for (i in 0 until sampleCount) {
                        floatChunk[i] = shortChunk[i] / 32768.0f
                    }

                    var pendingText: String? = null

                    // ─── 优化点 1：流式运行中解码与标点追加（锁内部） ───
                    synchronized(audioLock) {
                        val engine = recognizerRef.get()
                        val stream = currentStreamRef.get()
                        val puncEngine = punctuationRef.get()

                        if (engine != null && stream != null) {
                            if (hasRealAudioEntered) {
                                if (continuousSilenceCount <= maxTailBufferFrames) {
                                    stream.acceptWaveform(floatChunk, SAMPLE_RATE)

                                    loopCounter++
                                    if (loopCounter % 8 == 0) {
                                        try {
                                            engine.decode(stream)
                                            val resultObj = engine.getResult(stream)
                                            if (resultObj.text.isNotBlank()) {
                                                val cleanText = cleanSenseVoiceText(resultObj.text)
                                                // 如果标点符号引擎就绪，追加动态断句标点
                                                pendingText =
                                                    if (puncEngine != null && cleanText.isNotBlank()) {
                                                        puncEngine.addPunctuation(cleanText)
                                                    } else {
                                                        cleanText
                                                    }
                                            }
                                        } catch (e: Throwable) {
                                            Timber.e(e, "流式运行中解码失败")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!pendingText.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            serviceRef?.get()?.currentInputConnection?.setComposingText(
                                pendingText, 1
                            )
                        }
                    }

                    withContext(Dispatchers.Main) {
                        runCatching { SpeechUiBridge.onAmplitude?.invoke(amp) }
                    }

                    delay(5)
                }

                // ─── 优化点 2：松手时全量完美断句与上屏。
                    // 包在 NonCancellable 内：即使外部 job 已被取消，最终解码、
                    // setComposingText 与 onDone 也必须完整执行，避免末字丢失/UI 卡住。
                    val finalCleanText = withContext(NonCancellable) {
                        var result: String? = null
                        synchronized(audioLock) {
                            val engine = recognizerRef.get()
                            val stream = currentStreamRef.get()
                            val puncEngine = punctuationRef.get()

                            if (engine != null && stream != null && hasRealAudioEntered) {
                                try {
                                    engine.decode(stream)
                                    val finalResult = engine.getResult(stream)
                                    if (finalResult.text.isNotBlank()) {
                                        val cleanText = cleanSenseVoiceText(finalResult.text)
                                        // 全量文本送入标点模型加工
                                        result = if (puncEngine != null && cleanText.isNotBlank()) {
                                            puncEngine.addPunctuation(cleanText)
                                        } else {
                                            cleanText
                                        }
                                    }
                                } catch (e: Throwable) {
                                    Timber.e(e, "松手后最终解码失败")
                                }
                            }
                        }
                        result
                    }

                    withContext(NonCancellable + Dispatchers.Main) {
                        if (!finalCleanText.isNullOrBlank()) {
                            serviceRef?.get()?.currentInputConnection?.setComposingText(
                                finalCleanText, 1
                            )
                        }
                        runCatching { SpeechUiBridge.onDone?.invoke() }
                    }

            } catch (t: Throwable) {
                if (t is CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        runCatching { SpeechUiBridge.onDone?.invoke() }
                    }
                } else {
                    Timber.e(t, "录音及核心推理链异常")
                    withContext(NonCancellable + Dispatchers.Main) {
                        serviceRef?.get()?.let { toast(it, "录音异常") }
                        runCatching { SpeechUiBridge.onDone?.invoke() }
                    }
                }
            } finally {
                try {
                    rec?.stop()
                    rec?.release()
                } catch (_: Throwable) {
                }
                if (audioRecord == rec) {
                    audioRecord = null
                }
                synchronized(audioLock) {
                    try {
                        currentStreamRef.get()?.release()
                    } catch (_: Throwable) {
                    }
                    currentStreamRef.set(null)
                }
            }
        }
    }

    fun isHolding(): Boolean = isHolding.get()

    private fun cancelSession() {
        isHolding.set(false)
        audioJob?.cancel()
        audioJob = null
        runCatching { SpeechUiBridge.onDone?.invoke() }
        resetStateDirectly()
    }

    private fun resetStateDirectly() {
        isHolding.set(false)
        synchronized(audioLock) {
            try {
                currentStreamRef.get()?.release()
            } catch (_: Throwable) {
            }
            currentStreamRef.set(null)
        }
        clearReferences()
    }

    private fun clearReferences() {
        serviceRef?.clear()
        serviceRef = null
    }

    private fun calculateAmplitude(samples: ShortArray, count: Int): Float {
        var max = 0
        for (i in 0 until count) {
            val absSample = abs(samples[i].toInt())
            if (absSample > max) max = absSample
        }
        return max.toFloat() / 32768f
    }

    private fun cleanSenseVoiceText(rawText: String): String {
        return rawText.trim()
    }

    private fun toast(ctx: Context, msg: String) {
        ContextCompat.getMainExecutor(ctx).execute {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}