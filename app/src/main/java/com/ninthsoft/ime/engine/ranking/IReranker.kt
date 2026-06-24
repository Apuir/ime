package com.ninthsoft.ime.engine.ranking

import android.content.Context

interface IReranker {
    data class Result(val document: String, val score: Float)

    fun onCreate(context: Context)

    fun onDestroy()

    fun rerank(query: String, documents: List<String>): List<Result>
}