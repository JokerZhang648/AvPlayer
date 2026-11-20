package com.javrocket.avplay.service.huangguo

/**
 * 黄果图床 `pic.qnlycc.cn` 对封面做 AES-128-CBC（NoPadding）加密，
 * 与原 App `HuangGuoImageDecryptor` 一致。客户端用站点公开的 key/iv 解出 JPEG/WebP 再解码。
 */
internal object HuangGuoImageCrypto {
    private const val CDN_HOST = "pic.qnlycc.cn"

    fun prepareBytes(url: String, downloaded: ByteArray): ByteArray {
        if (!url.contains(CDN_HOST, ignoreCase = true)) return downloaded
        if (looksLikeImage(downloaded)) return downloaded
        return aesDecryptImage(downloaded) ?: downloaded
    }

    fun imageRequestHeaders(siteBaseUrl: String): Map<String, String> = mapOf(
        "Origin" to siteBaseUrl.trimEnd('/'),
        "Referer" to "${siteBaseUrl.trimEnd('/')}/",
    )

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) return true
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) return true
        return false
    }
}

internal expect fun aesDecryptImage(encrypted: ByteArray): ByteArray?