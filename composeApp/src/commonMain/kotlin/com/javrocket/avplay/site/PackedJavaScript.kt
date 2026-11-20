package com.javrocket.avplay.site

/**
 * Packer 混淆 JS 的离线解包 —— 逐条还原反编译里的
 * service/aggregator/PackedJavaScript.java（含 6 个护栏常量）。
 *
 * 原理：经典 Dean Edwards packer 的形状是
 *   eval(function(p,a,c,k,e,d){...}('payload',radix,count,'word|word|...'.split('|'),0,{}))
 *
 * 它不执行任何 JS，只做“字符串替换”：把 payload 里的 token（数字/字母）按 radix 解成下标，
 * 再去 dictionary 里取词替换回来。空字典项原样保留。
 *
 * 为什么必须这么做：很多站点的直链就藏在被 packer 混淆的字符串里，
 * 不先解包，正则永远匹配不到可播放地址。
 */
object PackedJavaScript {

    private const val MARKER = "eval(function(p,a,c,k,e,d)"

    // 护栏：防止超长/恶意页面把内存打爆（数值与原实现相同）
    private const val MAX_SOURCE_LENGTH = 1_500_000
    private const val MAX_BLOCKS = 32
    private const val MAX_PAYLOAD_LENGTH = 512_000
    private const val MAX_DICTIONARY_LENGTH = 256_000
    private const val MAX_WORDS = 20_000

    private data class Block(val payload: String, val radix: Int, val words: List<String>)

    /** 解出所有可解包的片段；解不出来就返回空列表（调用方当成“没有混淆”处理）。 */
    fun unpackAll(source: String): List<String> {
        if (source.isEmpty() || source.length > MAX_SOURCE_LENGTH) return emptyList()

        val out = ArrayList<String>()
        var from = 0
        while (out.size < MAX_BLOCKS) {
            val idx = source.indexOf(MARKER, from)
            if (idx < 0) break

            val block = parseBlock(source, idx)
            if (block != null) {
                out += replaceTokens(block.payload, block.radix, block.words)
            }
            from = idx + MARKER.length
        }
        return out
    }

    private fun parseBlock(source: String, markerIndex: Int): Block? {
        // 1) payload：标记后紧跟 "}('"
        var i = source.indexOf("}('", markerIndex)
        if (i < 0) return null
        val payloadStart = i + 3

        val quote1 = findQuote(source, payloadStart)
        if (quote1 < 0) return null

        val payloadRaw = source.substring(payloadStart, quote1)
        if (payloadRaw.length > MAX_PAYLOAD_LENGTH) return null
        val payload = payloadRaw.replace("\\'", "'")

        // 2) radix：payload 后第一个逗号到第二个逗号之间
        i = source.indexOf(',', quote1 + 1)
        if (i < 0) return null
        val radixEnd = source.indexOf(',', i + 1)
        if (radixEnd < 0) return null

        val radix = source.substring(i + 1, radixEnd).trim().toIntOrNull() ?: return null
        if (radix < 2 || radix > 62) return null

        // 3) count：跳过（原实现同样只用它做定位，不参与计算）
        val countEnd = source.indexOf(',', radixEnd + 1)
        if (countEnd < 0) return null

        // 4) dictionary：下一个单引号到它的配对引号
        val dictOpen = source.indexOf('\'', countEnd + 1)
        if (dictOpen < 0) return null
        val dictClose = findQuote(source, dictOpen + 1)
        if (dictClose < 0) return null

        val dictRaw = source.substring(dictOpen + 1, dictClose)
        if (dictRaw.length > MAX_DICTIONARY_LENGTH) return null

        val words = dictRaw.split('|')
        if (words.size > MAX_WORDS) return null

        return Block(payload, radix, words)
    }

    /** 找“真正的”收尾单引号：跳过被反斜杠转义的那些。 */
    private fun findQuote(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '\'' && !isEscaped(s, i)) return i
            i++
        }
        return -1
    }

    /** 数前面连续反斜杠的个数，奇数表示被转义。 */
    private fun isEscaped(s: String, index: Int): Boolean {
        var count = 0
        var j = index - 1
        while (j >= 0 && s[j] == '\\') {
            count++
            j--
        }
        return count % 2 == 1
    }

    /** 逐字符扫描，把 token 段换成字典词；非 token 字符原样输出。 */
    private fun replaceTokens(payload: String, radix: Int, words: List<String>): String {
        val sb = StringBuilder(payload.length)
        var i = 0
        while (i < payload.length) {
            val c = payload[i]
            if (!isTokenChar(c)) {
                sb.append(c)
                i++
                continue
            }
            var j = i
            while (j < payload.length && isTokenChar(payload[j])) j++

            val token = payload.substring(i, j)
            val idx = decodePackedIndex(token, radix)
            val word = if (idx in words.indices) words[idx] else null
            if (word.isNullOrEmpty()) {
                sb.append(payload, i, j)
            } else {
                sb.append(word)
            }
            i = j
        }
        return sb.toString()
    }

    private fun isTokenChar(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'

    /** radix 进制转十进制下标；非法数字或溢出返回 -1。 */
    internal fun decodePackedIndex(token: String, radix: Int): Int {
        if (token.isEmpty()) return -1
        var value = 0L
        for (c in token) {
            val digit = when {
                c in '0'..'9' -> c - '0'
                c in 'a'..'z' -> c - 'a' + 10
                c in 'A'..'Z' -> c - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            value = value * radix + digit
            if (value > Int.MAX_VALUE) return -1
        }
        return value.toInt()
    }
}
