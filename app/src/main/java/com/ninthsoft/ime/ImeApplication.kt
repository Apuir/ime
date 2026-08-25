package com.ninthsoft.ime

import android.app.Application
import com.ninthsoft.ime.engine.AppStartup
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class ImeApplication : Application() {
    enum class AppState { Starting, ResourcePreparing, EngineStarting, Finished }

    val applicationScope = MainScope() + CoroutineName(javaClass.name)
    private val _state = MutableStateFlow(AppState.Starting)
    val state: StateFlow<AppState> = _state.asStateFlow()

    companion object {
        private var instance: ImeApplication? = null

        fun getInstance() =
            instance ?: throw IllegalStateException("ime application is not created!")
    }

    fun notifyState(state: AppState) {
        _state.value = state
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        applicationScope.launch(Dispatchers.Default) {
            AppStartup.initialize(this@ImeApplication)
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
