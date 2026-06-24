package com.ninthsoft.ime.base.speech

object SpeechUiBridge {
    @Volatile
    var onRecordingStarted: (() -> Unit)? = null

    @Volatile
    var onAmplitude: ((Float) -> Unit)? = null

    @Volatile
    var onDone: (() -> Unit)? = null

    fun clear() {
        onRecordingStarted = null
        onAmplitude = null
        onDone = null
    }
}
