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
import kotlin.math.abs

object SherpaSpeechClient {
    private val recognizerRef = AtomicReference<OfflineRecognizer?>(null)
    private val currentStreamRef = AtomicReference<OfflineStream?>(null)
    private val punctuationRef = AtomicReference<OfflinePunctuation?>(null)

    private val isHolding = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null

    private val pendingComposingText = AtomicReference<String?>(null)
    private var uiSyncJob: Job? = null

    private var serviceRef: WeakReference<ImeInputMethodService>? = null
    private val initLock = Any()

    private const val SAMPLE_RATE = 16000
    private const val NOISE_THRESHOLD = 0.02f

    private fun initEngineIfNeeded(ctx: Context): Boolean {
        if (recognizerRef.get() != null) return true

        synchronized(initLock) {
            if (recognizerRef.get() != null) return true

            val appContext = ctx.applicationContext
            val voiceDir =
                File(appContext.getExternalFilesDir(null), "model/speech").also { it.mkdirs() }
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
                } catch (e: Exception) {
                    Timber.e(e, "⚠️ 外部 metadata.json 解析失败")
                }
            }

            val modelFile = File(voiceDir, modelName)
            if (!modelFile.exists() || !tokensFile.exists()) return false
            val punctModelFile = File(voiceDir, punctModelName)

            return try {
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

                if (punctModelFile.exists()) {
                    runCatching {
                        val punctConfig = OfflinePunctuationConfig(
                            model = OfflinePunctuationModelConfig(
                                ctTransformer = punctModelFile.absolutePath,
                                numThreads = numThreads,
                                debug = false,
                                provider = provider
                            )
                        )
                        punctuationRef.set(OfflinePunctuation(null, punctConfig))
                    }
                }
                true
            } catch (e: Throwable) {
                false
            }
        }
    }

    fun startHoldSession(service: ImeInputMethodService) {
        if (!isHolding.compareAndSet(false, true)) return
        isStopping.set(false)

        serviceRef = WeakReference(service)
        pendingComposingText.set(null)

        uiSyncJob = service.scope?.launch(Dispatchers.Main) {
            while (isActive && isHolding.get()) {
                delay(50)
                val text = pendingComposingText.getAndSet(null)
                if (!text.isNullOrBlank()) {
                    service.currentInputConnection?.setComposingText(text, 1)
                }
            }
        }

        service.scope?.launch {
            val initSuccess = withContext(Dispatchers.IO) { initEngineIfNeeded(service) }

            if (!isHolding.get()) {
                clearReferences()
                return@launch
            }

            if (initSuccess) {
                val engine = recognizerRef.get()
                if (engine != null) {
                    try {
                        val newStream = engine.createStream()
                        currentStreamRef.set(newStream)
                        startAudioStreaming(service)
                    } catch (t: Throwable) {
                        cancelSession()
                    }
                }
            } else {
                toast(service, "语音识别本地组件未就绪")
                cancelSession()
            }
        }
    }

    fun stopHoldSession() {
        if (!isHolding.compareAndSet(true, false)) return
        if (!isStopping.compareAndSet(false, true)) return

        val job = audioJob
        audioJob = null
        uiSyncJob?.cancel()
        uiSyncJob = null

        val svc = serviceRef?.get()

        if (svc != null) {
            svc.scope?.launch {
                val rec = audioRecord
                try {
                    rec?.stop()
                } catch (_: Throwable) {
                }

                job?.join()

                withContext(NonCancellable) {
                    var finalCleanText: String? = null
                    val engine = recognizerRef.get()
                    val stream = currentStreamRef.get()
                    val puncEngine = punctuationRef.get()

                    if (engine != null && stream != null) {
                        try {
                            engine.decode(stream)
                            val finalResult = engine.getResult(stream)
                            if (finalResult.text.isNotBlank()) {
                                val cleanText = cleanSenseVoiceText(finalResult.text)
                                finalCleanText = if (puncEngine != null && cleanText.isNotBlank()) {
                                    puncEngine.addPunctuation(cleanText)
                                } else {
                                    cleanText
                                }
                            }
                        } catch (e: Throwable) {
                            Timber.e(e, "松手后最终解码失败")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val ic = svc.currentInputConnection
                        if (ic != null) {
                            if (!finalCleanText.isNullOrBlank()) {
                                ic.setComposingText(finalCleanText, 1)
                                ic.finishComposingText()
                            } else {
                                ic.finishComposingText()
                            }
                        }
                        runCatching { SpeechUiBridge.onDone?.invoke() }
                    }
                    clearReferences()
                    isStopping.set(false)
                }
            }
        } else {
            job?.cancel()
            clearReferences()
            isStopping.set(false)
            runCatching { SpeechUiBridge.onDone?.invoke() }
        }
    }

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
                if (rec == null) return@launch

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
                                            val streamingText =
                                                if (puncEngine != null && cleanText.isNotBlank()) {
                                                    puncEngine.addPunctuation(cleanText)
                                                } else {
                                                    cleanText
                                                }
                                            pendingComposingText.set(streamingText)
                                        }
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        runCatching { SpeechUiBridge.onAmplitude?.invoke(amp) }
                    }
                    delay(5)
                }

            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        serviceRef?.get()?.let { toast(it, "录音异常") }
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
                try {
                    currentStreamRef.get()?.release()
                } catch (_: Throwable) {
                }
                currentStreamRef.set(null)
            }
        }
    }

    fun isHolding(): Boolean = isHolding.get()

    private fun cancelSession() {
        isHolding.set(false)
        isStopping.set(false)
        audioJob?.cancel()
        audioJob = null
        uiSyncJob?.cancel()
        uiSyncJob = null
        runCatching { SpeechUiBridge.onDone?.invoke() }
        resetStateDirectly()
    }

    private fun resetStateDirectly() {
        isHolding.set(false)
        isStopping.set(false)
        uiSyncJob?.cancel()
        uiSyncJob = null
        pendingComposingText.set(null)
        try {
            currentStreamRef.get()?.release()
        } catch (_: Throwable) {
        }
        currentStreamRef.set(null)
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

    private fun cleanSenseVoiceText(rawText: String): String = rawText.trim()

    private fun toast(ctx: Context, msg: String) {
        ContextCompat.getMainExecutor(ctx).execute {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }
}