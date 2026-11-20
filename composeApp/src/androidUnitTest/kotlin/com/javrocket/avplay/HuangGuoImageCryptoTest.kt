package com.javrocket.avplay

import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import com.javrocket.avplay.service.huangguo.aesDecryptImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HuangGuoImageCryptoTest {

    @Test
    fun decrypt_sample_cover_to_jpeg() {
        val path = System.getenv("HG_COVER_SAMPLE")
            ?: return // 本地可选：设 HG_COVER_SAMPLE 指向从 pic.qnlycc.cn 拉下的密文
        val encrypted = File(path).readBytes()
        val decrypted = aesDecryptImage(encrypted)
        assertNotNull(decrypted)
        assertTrue(decrypted!![0] == 0xFF.toByte() && decrypted[1] == 0xD8.toByte())
        val prepared = HuangGuoImageCrypto.prepareBytes(
            "https://pic.qnlycc.cn/upload_01/test.jpeg",
            encrypted,
        )
        assertEquals(0xFF.toByte(), prepared[0])
    }
}