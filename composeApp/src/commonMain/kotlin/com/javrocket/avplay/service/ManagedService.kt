package com.javrocket.avplay.service

/**
 * 站点服务统一契约 —— 对应反编译里的 service/ManagedService。
 * 原实现只有两个方法：getBaseUrl() / close()，这里保持同样的极简形状。
 */
interface ManagedService {
    fun getBaseUrl(): String
    fun close() = Unit
}
