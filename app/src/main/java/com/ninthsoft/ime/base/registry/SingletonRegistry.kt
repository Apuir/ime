package com.ninthsoft.ime.base.registry

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object SingletonRegistry {

    private val instances = ConcurrentHashMap<KClass<*>, Any>()

    /**
     * 注册实例：如果该类型已存在，则抛出异常，强制防止重复注册
     */
    fun <T : Any> registerIfAbsent(clazz: KClass<T>, instance: T): Boolean {
        if (instances.containsKey(clazz)) {
            return false
        }
        instances[clazz] = instance
        return true
    }

    /**
     * 注册实例
     */
    fun <T : Any> register(clazz: KClass<T>, instance: T) {
        instances[clazz] = instance
    }

    /**
     * 获取实例
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: KClass<T>): T {
        return instances[clazz] as? T
            ?: throw IllegalStateException("实例 ${clazz.simpleName} 未注册")
    }

    /**
     * 泛型便捷获取
     */
    inline fun <reified T : Any> get(): T {
        return get(T::class)
    }
}