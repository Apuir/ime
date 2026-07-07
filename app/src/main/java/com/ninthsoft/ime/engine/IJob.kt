package com.ninthsoft.ime.engine

interface IJob {
    fun sendJob(block: suspend () -> Unit)

    suspend fun <T> awaitJob(defaultValue: T, block: suspend () -> T): T
}