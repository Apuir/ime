package com.ninthsoft.ime.engine

import android.content.Context
import com.ninthsoft.ime.base.registry.SingletonRegistry
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object EngineFactory {

    private val instances = ConcurrentHashMap<KClass<out IEngine>, IEngine>()

    @Volatile
    private var currentEngine: IEngine? = null

    fun <T : IEngine> create(
        context: Context,
        clazz: KClass<T>,
    ): T {
        return try {
            clazz.java.getDeclaredConstructor().newInstance().also {
                it.initialize(context)
                SingletonRegistry.registerIfAbsent(clazz, it)
            }
        } catch (e: Exception) {
            throw RuntimeException(
                "无法创建引擎实例: ${clazz.simpleName}",
                e,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IEngine> getOrCreate(
        context: Context,
        clazz: KClass<T>,
    ): T {
        return instances.getOrPut(clazz) {
            create(context, clazz)
        } as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : IEngine> switchTo(
        context: Context,
        clazz: KClass<T>,
    ): T {
        val engine = getOrCreate(context, clazz)
        currentEngine = engine
        SingletonRegistry.register(IEngine::class,engine)
        return engine
    }

    fun current(): IEngine? = currentEngine

    @Suppress("UNCHECKED_CAST")
    fun <T : IEngine> currentAs(): T? {
        return currentEngine as? T
    }
}