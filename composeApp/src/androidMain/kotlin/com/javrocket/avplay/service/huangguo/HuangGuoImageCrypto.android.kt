package com.javrocket.avplay.service.huangguo

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private val KEY = "f5d965df75336270".encodeToByteArray()
private val IV = "97b60394abc2fbe1".encodeToByteArray()

internal actual fun aesDecryptImage(encrypted: ByteArray): ByteArray? {
    if (encrypted.isEmpty() || encrypted.size % 16 != 0) return null
    return runCatching {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        cipher.doFinal(encrypted)
    }.getOrNull()
}