package com.javrocket.avplay.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 操作超时档位。对应反编译里的 network/NetworkOperationTimeouts。 */
object Timeouts {
    const val QUICK = 8_000L
    const val STANDARD = 15_000L
    const val LONG = 30_000L
}

/**
 * 统一的“带超时的操作”包装 —— 对应反编译里的 service/ServiceOperationKt。
 *
 * 两个细节照原实现抄：
 * 1) 用 Box 包一层返回值，避免 block 合法返回 null 时被误判成超时
 *    （原实现用的是 OperationValue 包装类，同一个原因）；
 * 2) 超时抛具名 AppError.Timeout，且带 operation 名 —— 日志里能一眼定位是哪个接口慢。
 */
private class Box<T>(val value: T)

suspend fun <T> withOperationTimeout(
    operation: String,
    timeoutMs: Long = Timeouts.STANDARD,
    block: suspend () -> T,
): T {
    val boxed = withTimeoutOrNull(timeoutMs) { Box(block()) }
        ?: throw AppError.Timeout(operation, timeoutMs)
    return boxed.value
}

/**
 * 异常归一化：把任意 Throwable 收成 AppError，保证上层只需要处理 AppError。
 * 对应反编译里的 ErrorKt.toException + ApiResponseKt.getOrThrowMessage。
 */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is TimeoutCancellationException -> AppError.Timeout("unknown", 0)
    else -> AppError.Network(this)
}

/**
 * 业务调用入口：成功给结果，失败给 AppError，取消要原样抛出（不能被吞）。
 * 对应反编译里的 serviceApiRequest —— 那里也是“永不把异常直接抛给 UI”。
 */
suspend fun <T> runServiceOperation(
    operation: String,
    timeoutMs: Long = Timeouts.STANDARD,
    block: suspend () -> T,
): Result<T> = try {
    Result.success(withOperationTimeout(operation, timeoutMs, block))
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e.toAppError())
}

/** 是否需要提示“网络超时”而不是别的错误。对应反编译里的 isNetworkTimeout。 */
fun Throwable.isNetworkTimeout(): Boolean =
    this is AppError.Timeout || this is AppError.Network || this is TimeoutCancellationException
