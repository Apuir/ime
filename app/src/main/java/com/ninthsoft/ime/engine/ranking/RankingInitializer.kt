package com.ninthsoft.ime.engine.ranking

import android.content.Context
import androidx.startup.Initializer
import com.ninthsoft.ime.base.logger.LoggerInitializer
import com.ninthsoft.ime.base.registry.SingletonRegistry

class RankingInitializer : Initializer<IReranker> {

    override fun create(contex: Context): IReranker {
        val reranker = BGEReranker()
        reranker.onCreate(context = contex)
        SingletonRegistry.register(IReranker::class, reranker)
        return reranker
    }

    override fun dependencies(): List<Class<out Initializer<*>?>?> {
        return listOf(LoggerInitializer::class.java)
    }
}