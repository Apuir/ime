// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import com.ninthsoft.ime.engine.event.KeyModifiers
import com.ninthsoft.ime.engine.rime.data.DataManager
import com.ninthsoft.ime.engine.rime.data.opencc.OpenCCDictManager
import com.ninthsoft.ime.base.util.appContext
import com.ninthsoft.ime.base.util.isStorageAvailable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

class Rime : RimeApi, RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()

    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    override var schemaCached = RimeSchema(".default")
        private set

    override var statusCached = StatusProto()
        private set

    override var compositionCached = CompositionProto()
        private set

    override var hasMenu: Boolean = false
        private set

    override var paging: Boolean = false
        private set

    private val dispatcher = RimeDispatcher(
        object : RimeDispatcher.RimeController {
            override fun nativeStartup() {
                startRime(false)
                lifecycleRegistry.emitState(RimeLifecycle.State.READY)
            }

            override fun nativeFinalize() {
                shutdown()
            }
        },
    )

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T =
        withContext(dispatcher) { block() }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentSchema() == ".default"
    }

    override suspend fun deploy() = withRimeContext {
        shutdown()
        startRime(true)
    }

    override suspend fun updateConfig() = withRimeContext {
        shutdown()
        startRime(false)
    }

    override suspend fun syncUserData(): Boolean = withRimeContext {
        Companion.syncUserData()
    }

    override suspend fun processKey(value: Int, modifiers: UInt, isVirtual: Boolean): Boolean =
        withRimeContext { processKeyInner(value, modifiers.toInt(), isVirtual) }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext { processKeyInner(value.value, modifiers.toInt(), isVirtual) }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        if (Companion.simulateKeySequence(sequence)) {
            val commit = getCommit()
            val input = Companion.getRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse { commit }
                true
            } else {
                emitResponse { CommitProto(sequence) }
                false
            }
        } else {
            false
        }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        Companion.selectCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        Companion.deleteCandidate(idx, global).also { emitResponse() }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        Companion.changeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setCaretPos(position)
        emitResponse()
    }

    override suspend fun setInput(input: String) = withRimeContext {
        Companion.setInput(input).also { emitResponse() }
    }

    override suspend fun availableSchemata(): Array<SchemaItem> =
        withRimeContext { getAvailableSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> =
        withRimeContext { getSelectedSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) =
        withRimeContext { selectSchemas(schemaIds) }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentSchema() }

    override suspend fun selectSchema(schemaId: String) =
        withRimeContext { Companion.selectSchema(schemaId) }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentSchema())
    }

    override suspend fun commitComposition(): Boolean =
        withRimeContext { Companion.commitComposition().also { if (it) emitResponse() } }

    override suspend fun clearComposition() = withRimeContext {
        Companion.clearComposition()
        emitResponse()
    }

    override suspend fun freeContext() = withRimeContext {
        Companion.freeContext()
        emitResponse()
    }

    override suspend fun getRawInput(): String = withRimeContext { Companion.getRawInput() }

    override suspend fun getInputConfirmedPosition(): Int =
        withRimeContext { Companion.getInputConfirmedPosition() }

    override suspend fun setRuntimeOption(option: String, value: Boolean) = withRimeContext {
        setOption(option, value)
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getOption(option)
    }

    override suspend fun getCandidates(startIndex: Int, limit: Int): Array<CandidateProto> =
        withRimeContext { Companion.getCandidates(startIndex, limit) }

    private fun startRime(fullCheck: Boolean) {
        DataManager.sync()
        val sharedDataDir = DataManager.sharedDataDir.absolutePath
        val userDataDir = DataManager.userDataDir.absolutePath
        Timber.d("Starting rime: shared=$sharedDataDir user=$userDataDir fullCheck=$fullCheck")
        bootstrap(sharedDataDir, userDataDir, "1.0", fullCheck)
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        val handled = processKey(value, modifiers)
        emitResponse()
        if (!handled) {
            handleMessage(RimeMessage.MessageType.Key.ordinal, arrayOf(value, modifiers, isVirtual))
        }
        return handled
    }

    private fun emitResponse(commit: () -> CommitProto = { getCommit() }) {
        handleMessage(RimeMessage.MessageType.Commit.ordinal, arrayOf(commit()))
        val context = getContext()
        handlePreedit(context.composition)
        if (getOption("paging_mode")) {
            handleMessage(RimeMessage.MessageType.Menu.ordinal, arrayOf(context.menu))
        } else {
            handleMessage(RimeMessage.MessageType.Candidate.ordinal, getBulkCandidates())
        }
        handleMessage(RimeMessage.MessageType.Status.ordinal, arrayOf(getStatus()))
    }

    private fun handlePreedit(composition: CompositionProto) {
        handleMessage(
            RimeMessage.MessageType.InlinePreedit.ordinal, arrayOf(composition.preedit ?: "")
        )
        handleMessage(RimeMessage.MessageType.Composition.ordinal, arrayOf(composition))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                statusCached = getStatus()
                schemaCached = RimeSchema(it.data.id)
            }

            is RimeMessage.OptionMessage -> {
                statusCached = getStatus()
                updateSchemaCached(statusCached)
            }

            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Start) {
                    OpenCCDictManager.buildOpenCCDict()
                }
            }

            is RimeMessage.CompositionMessage -> {
                compositionCached = it.data
            }

            is RimeMessage.CandidateMenuMessage -> {
                paging = it.data.pageNumber != 0
                hasMenu = it.data.candidates.isNotEmpty()
            }

            is RimeMessage.CandidateListMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
            }

            is RimeMessage.StatusMessage -> {
                statusCached = it.data
                updateSchemaCached(it.data)
            }

            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val (schemaId, schemaName) = status
        if (schemaId != schemaCached.schemaId) {
            schemaCached = RimeSchema(schemaId)
        }
    }

    fun startup() {
        if (!appContext.isStorageAvailable()) {
            Timber.w("Skip starting rime: storage not available!")
            return
        }
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            Timber.w("Skip starting rime: not at stopped state!")
            return
        }
        registerMessageHandler(::handleRimeMessage)
        lifecycleRegistry.emitState(RimeLifecycle.State.STARTING)
        dispatcher.start()
    }

    fun finalize() {
        if (lifecycle.currentState != RimeLifecycle.State.READY) {
            Timber.w("Skip stopping rime: not at ready state!")
            return
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPING)
        Timber.i("Rime finalize()")
        dispatcher.stop().let {
            if (it.isNotEmpty()) {
                Timber.w("${it.size} job(s) didn't get a chance to run!")
            }
        }
        lifecycleRegistry.emitState(RimeLifecycle.State.STOPPED)
        unregisterMessageHandler(::handleRimeMessage)
    }

    companion object {
        private val messageFlow_ = MutableSharedFlow<RimeMessage<*>>(
            extraBufferCapacity = 15,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        private val rimeMessageHandlers = ArrayList<(RimeMessage<*>) -> Unit>()

        init {
            System.loadLibrary("rime_jni")
        }

        @JvmStatic
        external fun bootstrap(
            sharedDir: String, userDir: String, versionName: String, fullCheck: Boolean
        )

        @JvmStatic
        external fun shutdown()

        @JvmStatic
        external fun deploySchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployConfigFile(fileName: String, versionKey: String): Boolean

        @JvmStatic
        external fun syncUserData(): Boolean

        @JvmStatic
        external fun processKey(keycode: Int, mask: Int): Boolean

        @JvmStatic
        external fun commitComposition(): Boolean

        @JvmStatic
        external fun clearComposition()

        @JvmStatic
        external fun freeContext()

        @JvmStatic
        external fun getCommit(): CommitProto

        @JvmStatic
        external fun getContext(): ContextProto

        @JvmStatic
        external fun getStatus(): StatusProto

        @JvmStatic
        external fun setOption(option: String, value: Boolean)

        @JvmStatic
        external fun getOption(option: String): Boolean

        @JvmStatic
        external fun getSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentSchema(): String

        @JvmStatic
        external fun selectSchema(schemaId: String): Boolean

        @JvmStatic
        external fun simulateKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRawInput(): String

        @JvmStatic
        external fun setInput(keySequence: String): Boolean

        @JvmStatic
        external fun getCaretPos(): Int

        @JvmStatic
        external fun setCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getCandidates(startIndex: Int, limit: Int): Array<CandidateProto>

        @JvmStatic
        external fun getBulkCandidates(): Array<Any>

        @JvmStatic
        external fun getInputConfirmedPosition(): Int

        @JvmStatic
        fun handleMessage(type: Int, params: Array<Any>) {
            val t = params[0]
            val message = RimeMessage.nativeCreate(type, params)
            rimeMessageHandlers.forEach { it.invoke(message) }
            messageFlow_.tryEmit(message)
        }

        private fun registerMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (handler !in rimeMessageHandlers) {
                rimeMessageHandlers.add(handler)
            }
        }

        private fun unregisterMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
