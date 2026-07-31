package com.ninthsoft.ime

import android.app.Application
import com.ninthsoft.ime.engine.AppStartup
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class ImeApplication : Application() {
    enum class InitState { INITIALIZING, EXTRACTING, STARTING_ENGINE, DONE }

    val coroutineScope = MainScope() + CoroutineName(javaClass.name)
    private val _initState = MutableStateFlow(InitState.INITIALIZING)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    companion object {
        private var instance: ImeApplication? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("ime application is not created!")
    }

    fun notifyState(state: InitState) {
        _initState.value = state
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        coroutineScope.launch(Dispatchers.Default) {
            AppStartup.initialize(this@ImeApplication)
        }
    }
}