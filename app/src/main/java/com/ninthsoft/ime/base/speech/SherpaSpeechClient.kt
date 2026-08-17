package com.ninthsoft.ime.base.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.ninthsoft.ime.engine.rime.data.DataManager
import com.ninthsoft.ime.input.ImeInputMethodService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

object SherpaSpeechClient {
    private const val SAMPLE_RATE = 16000
    private const val CHUNK_MS = 40
    private const val FINAL_TAIL_PADDING_MS = 800
    private const val PARTIAL_EMIT_MIN_INTERVAL_MS = 80L

    private val recognizerRef = AtomicReference<OnlineRecognizer?>(null)
    private val streamRef = AtomicReference<OnlineStream?>(null)
    private val qnnRuntimeRef = AtomicReference<QnnRuntime?>(null)

    private val holding = AtomicBoolean(false)
    private val composingText = AtomicReference<String?>(null)

    private var lastRawText: String? = null
    private var lastEmittedText: String? = null
    private var lastEmitUptimeMs = 0L

    private val initLock = Any()
    private val audioLock = Any() // 新增：专用音频流线程锁，防止并发冲突

    private var audioJob: Job? = null
    private var uiJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var serviceRef: WeakReference<ImeInputMethodService>? = null

    private data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
        val isQnn: Boolean,
    )

    private data class QnnRuntime(
        val backend: File,
        val system: File,
        val target: File,
    )

    private fun findModelFiles(dir: File, qnn: Boolean): ModelFiles? {
        val tokens = File(dir, "tokens.txt")
        if (!tokens.isFile) return null
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val ext = if (qnn) "bin" else "onnx"
        fun pick(prefix: String) = files.firstOrNull {
            it.extension.equals(ext, ignoreCase = true) && it.nameWithoutExtension.contains(
                prefix, ignoreCase = true
            )
        }

        val encoder = pick("encoder") ?: return null
        val decoder = pick("decoder") ?: return null
        val joiner = pick("joiner") ?: return null
        return ModelFiles(tokens, encoder, decoder, joiner, qnn)
    }

    fun isQnnRuntimeSupported(context: Context): Boolean =
        Build.SUPPORTED_ABIS.contains("arm64-v8a")

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun prepareQnnRuntime(context: Context): Boolean {
        qnnRuntimeRef.get()?.let { return true }
        return runCatching {
            val cdsp = File(context.filesDir, "cdsp")
            prepareCdspFiles(context, cdsp)
            listOf("QnnSystem", "QnnHtp").forEach { System.loadLibrary(it) }
            OnlineRecognizer.prependAdspLibraryPath(cdsp.absolutePath)
            qnnRuntimeRef.set(
                QnnRuntime(
                    backend = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so"),
                    system = File(context.applicationInfo.nativeLibraryDir, "libQnnSystem.so"),
                    target = cdsp,
                ),
            )
            Timber.i("QNN runtime prepared: DSP_PATH=%s", cdsp.absolutePath)
            true
        }.getOrElse {
            Timber.e(it, "Failed to prepare QNN runtime")
            false
        }
    }

    private fun prepareCdspFiles(context: Context, cdspDir: File) {
        val marker = File(cdspDir, ".prepared")
        if (marker.isFile) return
        cdspDir.deleteRecursively()
        cdspDir.mkdirs()
        copyAssetDirectory(context, "cdsp", cdspDir)
        marker.createNewFile()
    }

    private fun copyAssetDirectory(context: Context, assetPath: String, targetDir: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            context.assets.open(assetPath).use { input ->
                val target = File(targetDir, assetPath.substringAfterLast('/'))
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
            targetDir.listFiles()?.forEach { it.setExecutable(true) }
            return
        }
        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val target = File(targetDir, entry)
            val children = context.assets.list(childAssetPath).orEmpty()
            if (children.isNotEmpty()) {
                target.mkdirs()
                copyAssetDirectory(context, childAssetPath, target)
            } else {
                context.assets.open(childAssetPath).use { input ->
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.setReadable(true)
                target.setExecutable(true)
            }
        }
    }

    private fun notifyModelMissing(context: Context, provider: ModelProvider) {
        val cb = SpeechUiBridge.onModelMissing ?: return
        ContextCompat.getMainExecutor(context).execute { cb(provider) }
    }

    private fun initEngine(context: Context, silent: Boolean = false): Boolean {
        if (recognizerRef.get() != null) return true
        synchronized(initLock) {
            if (recognizerRef.get() != null) return true

            val dir = DataManager.speechModelDir
            val qnnSupported = isQnnRuntimeSupported(context)
            val qnnFiles = if (qnnSupported) findModelFiles(dir, qnn = true) else null

            val (files, useQnn) = if (qnnFiles != null && prepareQnnRuntime(context)) {
                qnnFiles to true
            } else {
                val cpuFiles = findModelFiles(dir, qnn = false)
                if (cpuFiles == null) {
                    if (!silent) {
                        notifyModelMissing(
                            context, if (qnnSupported) ModelProvider.QNN else ModelProvider.CPU
                        )
                    }
                    Timber.w("No available speech model in %s", dir)
                    return false
                }
                cpuFiles to false
            }

            return try {
                val transducer = if (useQnn) {
                    val rt = qnnRuntimeRef.get() ?: error("QNN runtime missing")
                    OnlineTransducerModelConfig(
                        encoder = "",
                        decoder = "",
                        joiner = "",
                        qnnConfig = QnnConfig(
                            backendLib = rt.backend.absolutePath,
                            systemLib = rt.system.absolutePath,
                            contextBinary = "${files.encoder.absolutePath},${files.decoder.absolutePath},${files.joiner.absolutePath}",
                        ),
                    )
                } else {
                    OnlineTransducerModelConfig(
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath,
                        joiner = files.joiner.absolutePath,
                    )
                }

                val modelConfig = OnlineModelConfig(
                    transducer = transducer,
                    tokens = files.tokens.absolutePath,
                    numThreads = if (useQnn) 1 else Runtime.getRuntime().availableProcessors()
                        .coerceIn(1, 4),
                    debug = false,
                    provider = if (useQnn) "qnn" else "cpu",
                    modelType = if (useQnn) "zipformer" else "",
                )
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                    enableEndpoint = false,
                )
                recognizerRef.set(OnlineRecognizer(null, config))
                val encoderPath = files.encoder.absolutePath
                val engineVariant = when {
                    useQnn -> "qnn"
                    files.isQnn -> "qnn-fallback-cpu"
                    files.encoder.nameWithoutExtension.contains("int8", ignoreCase = true) -> "int8"
                    else -> "standard"
                }
                Timber.i("Creating OnlineRecognizer: encoder=$encoderPath, bpe=false, variant=$engineVariant")
                true
            } catch (t: Throwable) {
                Timber.e(t, "Sherpa recognizer initialization failed")
                false
            }
        }
    }

    fun preStartSync(context: Context) {
        val app = context.applicationContext as? com.ninthsoft.ime.ImeApplication ?: return
        app.coroutineScope.launch(Dispatchers.IO) {
            runCatching { initEngine(app, silent = true) }.onFailure {
                Timber.w(it, "Sherpa QNN prewarm failed")
            }
        }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext as? com.ninthsoft.ime.ImeApplication ?: return
        app.coroutineScope.launch(Dispatchers.IO) {
            runCatching { initEngine(app, silent = true) }
        }
    }

    fun isModelReady(context: Context): Boolean {
        val dir = DataManager.speechModelDir
        return findModelFiles(dir, qnn = true) != null || findModelFiles(dir, qnn = false) != null
    }

    suspend fun downloadModel(
        context: Context,
        onProgress: (ModelDownloader.Progress) -> Unit = {},
        onExtract: (current: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = ModelDownloader.download(context, onProgress, onExtract)

    fun startHoldSession(service: ImeInputMethodService) {
        if (!holding.compareAndSet(false, true)) return
        Timber.i("startHoldSession")
        serviceRef = WeakReference(service)
        composingText.set(null)
        lastRawText = null
        lastEmittedText = null
        lastEmitUptimeMs = 0L

        uiJob = service.scope?.launch(Dispatchers.Main) {
            while (isActive && holding.get()) {
                composingText.getAndSet(null)?.let { text ->
                    service.currentInputConnection?.setComposingText(text, 1)
                }
                delay(50)
            }
        }

        service.scope?.launch {
            val ready = withContext(Dispatchers.IO) { initEngine(service) }
            if (!holding.get()) {
                resetState()
                return@launch
            }
            if (!ready) {
                cancelSession()
                return@launch
            }
            try {
                val engine = recognizerRef.get()
                if (engine != null) {
                    synchronized(audioLock) {
                        streamRef.set(engine.createStream())
                    }
                    startAudioStreaming(service)
                } else {
                    cancelSession()
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to create recognition stream")
                cancelSession()
            }
        }
    }

    fun stopHoldSession() {
        if (!holding.compareAndSet(true, false)) return
        uiJob?.cancel()
        uiJob = null
        audioRecord?.runCatching { stop() }
        audioJob?.let { job ->
            serviceRef?.get()?.scope?.launch {
                job.join()
                finishSession()
            }
        } ?: finishSession()
    }

    private fun startAudioStreaming(service: ImeInputMethodService) {
        if (ContextCompat.checkSelfPermission(
                service, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val intent = Intent(
                service, SpeechPermissionActivity::class.java
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { service.startActivity(intent) }
            cancelSession()
            return
        }

        audioJob = service.scope?.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            try {
                val channel = AudioFormat.CHANNEL_IN_MONO
                val format = AudioFormat.ENCODING_PCM_16BIT
                val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, format)
                val chunkSamples = SAMPLE_RATE * CHUNK_MS / 1000
                val chunkBytes = chunkSamples * 2
                val bufferSize = minBuffer.coerceAtLeast(chunkBytes * 2)

                recorder = listOf(
                    MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION
                ).firstNotNullOfOrNull { source ->
                    runCatching {
                        AudioRecord(source, SAMPLE_RATE, channel, format, bufferSize)
                    }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                } ?: error("Unable to initialize AudioRecord")

                audioRecord = recorder
                recorder.startRecording()

                withContext(Dispatchers.Main) {
                    runCatching { SpeechUiBridge.onRecordingStarted?.invoke() }
                }

                val bytes = ByteArray(chunkBytes)
                // 优化：循环外预分配缓冲区，彻底根除高频 GC 抖动
                val shortChunk = ShortArray(chunkSamples)
                val floatChunk = FloatArray(chunkSamples)

                while (isActive && holding.get() && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val count = recorder.read(bytes, 0, bytes.size)
                    if (count < 0) break
                    if (count == 0) {
                        delay(10.milliseconds)
                        continue
                    }

                    val sampleCount = count / 2
                    ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .get(shortChunk, 0, sampleCount)

                    var maxAmp = 0
                    for (i in 0 until sampleCount) {
                        val v = abs(shortChunk[i].toInt())
                        if (v > maxAmp) maxAmp = v
                        floatChunk[i] = shortChunk[i] / 32768f
                    }
                    val amplitude = maxAmp / 32768f

                    synchronized(audioLock) {
                        val engine = recognizerRef.get()
                        val stream = streamRef.get()
                        if (engine != null && stream != null) {
                            // Do not gate ASR input by amplitude. Short pauses are part of
                            // the utterance, and dropping them can make later characters
                            // disappear from a streaming transducer's context.
                            stream.acceptWaveform(
                                if (sampleCount == floatChunk.size) floatChunk
                                else floatChunk.copyOf(sampleCount),
                                SAMPLE_RATE,
                            )
                            var loops = 0
                            while (engine.isReady(stream) && loops++ < 64) {
                                engine.decode(stream)
                            }

                            val rawText = engine.getResult(stream).text.trim()
                            if (rawText.isNotEmpty() && rawText != lastRawText) {
                                lastRawText = rawText
                                val now = SystemClock.uptimeMillis()
                                if (now - lastEmitUptimeMs >= PARTIAL_EMIT_MIN_INTERVAL_MS) {
                                    val partial = normalizeCjkSpacing(rawText)
                                    if (partial.isNotEmpty() && partial != lastEmittedText) {
                                        lastEmittedText = partial
                                        lastEmitUptimeMs = now
                                        composingText.set(partial)
                                    }
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        runCatching { SpeechUiBridge.onAmplitude?.invoke(amplitude) }
                    }

                    delay(5.milliseconds)
                }

                synchronized(audioLock) {
                    val engine = recognizerRef.get()
                    val stream = streamRef.get()
                    if (engine != null && stream != null) {
                        try {
                            val tail = FloatArray(SAMPLE_RATE * FINAL_TAIL_PADDING_MS / 1000)
                            stream.acceptWaveform(tail, SAMPLE_RATE)
                            stream.inputFinished()

                            var loops = 0
                            while (engine.isReady(stream) && loops++ < 512) {
                                engine.decode(stream)
                            }
                            val finalText = normalizeCjkSpacing(engine.getResult(stream).text)
                            finalText.takeIf { it.isNotEmpty() }?.let {
                                composingText.set(it)
                            }
                        } catch (e: Throwable) {
                            Timber.e(e, "Final decode failed")
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    Timber.e(t, "Audio recording or inference failed")
                    withContext(NonCancellable + Dispatchers.Main) {
                        toast(service, "录音异常")
                    }
                    cancelSession()
                }
            } finally {
                recorder?.runCatching { stop() }
                recorder?.release()
                if (audioRecord === recorder) audioRecord = null
                synchronized(audioLock) {
                    streamRef.getAndSet(null)?.runCatching { release() }
                }
            }
        }
    }

    private fun finishSession() {
        val service = serviceRef?.get()
        service?.scope?.launch(Dispatchers.Main) {
            val text = composingText.getAndSet(null)
            if (!text.isNullOrBlank()) {
                service.currentInputConnection?.setComposingText(text, 1)
            }
            service.currentInputConnection?.finishComposingText()
            SpeechUiBridge.onDone?.invoke()
            resetState()
        } ?: resetState()
    }

    private fun cancelSession() {
        holding.set(false)
        audioJob?.cancel()
        audioJob = null
        uiJob?.cancel()
        uiJob = null
        val service = serviceRef?.get()
        val runUi = Runnable {
            runCatching {
                SpeechUiBridge.onFailed?.invoke() ?: SpeechUiBridge.onDone?.invoke()
            }
        }
        if (service != null) {
            ContextCompat.getMainExecutor(service).execute(runUi)
        } else {
            runUi.run()
        }
        resetState()
    }

    private fun resetState() {
        holding.set(false)
        uiJob?.cancel()
        uiJob = null
        audioJob = null
        synchronized(audioLock) {
            streamRef.getAndSet(null)?.runCatching { release() }
        }
        serviceRef?.clear()
        serviceRef = null
    }

    private fun normalizeCjkSpacing(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val chars = trimmed.toCharArray()
        val output = StringBuilder(trimmed.length)
        var i = 0
        while (i < chars.size) {
            if (chars[i].isWhitespace()) {
                var nextIndex = i + 1
                while (nextIndex < chars.size && chars[nextIndex].isWhitespace()) nextIndex++
                val previous = output.lastOrNull()
                val next = chars.getOrNull(nextIndex)
                val betweenCjk =
                    previous != null && next != null && isCjkOrPunctuation(previous) && isCjkOrPunctuation(
                        next
                    )
                val beforeAsciiPunctuation = next != null && next in ".,!?;:%)]}"
                if (!betweenCjk && !beforeAsciiPunctuation) {
                    repeat(nextIndex - i) { output.append(' ') }
                }
                i = nextIndex
            } else {
                output.append(chars[i++])
            }
        }
        return output.toString()
    }

    private fun isCjkOrPunctuation(ch: Char): Boolean =
        ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' || ch in '\uF900'..'\uFAFF' || ch in '！'..'～' || ch in '\u3000'..'\u303F' || ch in '\uFF00'..'\uFFEF' || ch in '\uFE30'..'\uFE4F'

    private fun toast(context: Context, text: String) {
        ContextCompat.getMainExecutor(context).execute {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}