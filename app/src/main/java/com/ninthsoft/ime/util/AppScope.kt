package com.ninthsoft.ime.util


import com.ninthsoft.ime.ImeApplication
import kotlinx.coroutines.CoroutineScope

val appScope: CoroutineScope get() = ImeApplication.getInstance().coroutineScope
