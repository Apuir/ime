package com.ninthsoft.ime.base.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import timber.log.Timber
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

class OnnxEngine : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var isLoaded = false

    private val sessionLock = Any()

    val inputNames: Set<String>
        get() = session?.inputNames ?: emptySet()

    val outputNames: Set<String>
        get() = session?.outputNames ?: emptySet()

    val isModelLoaded: Boolean
        get() = isLoaded

    fun loadModel(modelPath: String, options: SessionConfig = SessionConfig()) {
        synchronized(sessionLock) {
            closeSession()
            try {
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(options.optLevel)
                    setExecutionMode(options.executionMode)
                    setIntraOpNumThreads(options.intraOpThreads)
                    setInterOpNumThreads(options.interOpThreads)
                    setMemoryPatternOptimization(options.memoryPatternOptimization)
                    if (options.enableCpuMemArena) {
                        addConfigEntry("session.use_env_allocators", "1")
                    }
                    when (options.executionProvider) {
                        ExecutionProvider.NNAPI -> addNnapi()
                        ExecutionProvider.XNNPACK -> addXnnpack(emptyMap())
                        ExecutionProvider.CPU -> {}
                    }
                }
                session = env.createSession(modelPath, sessionOptions)
                isLoaded = true
                Timber.i("ONNX model loaded: $modelPath")
                logModelMetadata()
            } catch (e: Exception) {
                isLoaded = false
                Timber.e(e, "Failed to load ONNX model: $modelPath")
                throw e
            }
        }
    }

    fun run(inputs: Map<String, OnnxTensor>): OrtSession.Result {
        val s = requireSession()
        return s.run(inputs)
    }

    fun run(inputName: String, data: FloatArray): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, data)
        return try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
    }

    fun run(inputName: String, data: FloatArray, outputNames: Set<String>): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, data)
        return try {
            s.run(mapOf(inputName to tensor), outputNames)
        } finally {
            tensor.close()
        }
    }

    fun run(inputName: String, data: IntArray): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, data)
        return try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
    }

    fun run(inputName: String, data: LongArray): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, data)
        return try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
    }

    fun run(inputName: String, buffer: FloatBuffer, shape: LongArray): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, buffer, shape)
        return try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
    }

    fun run(inputName: String, buffer: IntBuffer, shape: LongArray): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, buffer, shape)
        return try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
    }

    fun run(inputName: String, buffer: LongBuffer, shape: LongArray): OrtSession.Result {
        val s = requireSession()
        val tensor = OnnxTensor.createTensor(env, buffer, shape)
        return try {
            s.run(mapOf(inputName to tensor))
        } finally {
            tensor.close()
        }
    }

    fun runAllOutputs(inputs: Map<String, OnnxTensor>): OrtSession.Result {
        val s = requireSession()
        return s.run(inputs, s.outputNames)
    }

    fun getInputInfo(): Map<String, TensorInfo> {
        val s = requireSession()
        return s.inputInfo.entries.associate { (name, info) ->
            name to info.info as TensorInfo
        }
    }

    fun getOutputInfo(): Map<String, TensorInfo> {
        val s = requireSession()
        return s.outputInfo.entries.associate { (name, info) ->
            name to info.info as TensorInfo
        }
    }

    fun hasInput(name: String): Boolean = session?.inputNames?.contains(name) == true

    fun hasOutput(name: String): Boolean = session?.outputNames?.contains(name) == true

    @Suppress("UNCHECKED_CAST")
    fun extractFloatData(output: OnnxTensor): Array<FloatArray> {
        return output.value as Array<FloatArray>
    }

    @Suppress("UNCHECKED_CAST")
    fun extractFloatDataFlat(output: OnnxTensor): FloatArray {
        return output.value as FloatArray
    }

    @Suppress("UNCHECKED_CAST")
    fun extractIntData(output: OnnxTensor): Array<IntArray> {
        return output.value as Array<IntArray>
    }

    @Suppress("UNCHECKED_CAST")
    fun extractLongData(output: OnnxTensor): Array<LongArray> {
        return output.value as Array<LongArray>
    }

    @Suppress("UNCHECKED_CAST")
    fun extractStringData(output: OnnxTensor): Array<String> {
        return output.value as Array<String>
    }

    private fun requireSession(): OrtSession {
        return session
            ?: throw IllegalStateException("ONNX model not loaded. Call loadModel() first.")
    }

    private fun logModelMetadata() {
        val s = session ?: return
        Timber.d("ONNX model inputs: ${s.inputNames}")
        Timber.d("ONNX model outputs: ${s.outputNames}")
        s.inputInfo.forEach { (name, info) ->
            val tensorInfo = info.info as? TensorInfo
            if (tensorInfo != null) {
                Timber.d("  Input '$name': type=${tensorInfo.type}, shape=${tensorInfo.shape.contentToString()}")
            }
        }
        s.outputInfo.forEach { (name, info) ->
            val tensorInfo = info.info as? TensorInfo
            if (tensorInfo != null) {
                Timber.d("  Output '$name': type=${tensorInfo.type}, shape=${tensorInfo.shape.contentToString()}")
            }
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
        isLoaded = false
    }

    override fun close() {
        synchronized(sessionLock) {
            closeSession()
        }
        Timber.d("OnnxEngine closed")
    }

    data class SessionConfig(
        val intraOpThreads: Int = 2,
        val interOpThreads: Int = 1,
        val optLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.BASIC_OPT,
        val executionMode: OrtSession.SessionOptions.ExecutionMode = OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL,
        val executionProvider: ExecutionProvider = ExecutionProvider.CPU,
        val memoryPatternOptimization: Boolean = true,
        val enableCpuMemArena: Boolean = true,
    )

    enum class ExecutionProvider {
        CPU, NNAPI, XNNPACK,
    }
}
