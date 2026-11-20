package com.javrocket.avplay.parser

/**
 * 解析公共工具 —— 对应反编译里的 parser/ParserUtilsKt、JsonUtilsKt 里那批小函数。
 *
 * 这批函数看起来琐碎，但决定了“相对链接能不能用、番号能不能对上、标题有没有脏字符”。
 * 站点改版时最先出问题的就是这里，所以单独成文件并全部可单测。
 */

/** 相对地址转绝对。HTML 里全是相对路径，不转就没法请求图片/详情。 */
fun absolutize(baseUrl: String, href: String): String {
    val raw = href.trim()
    if (raw.isBlank()) return ""
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
    if (raw.startsWith("//")) return "https:$raw"

    val base = baseUrl.trim().trimEnd('/')
    // 以 "/" 开头的站内绝对路径必须拼 origin 而不是 baseUrl：
    // javlibrary 的 baseUrl 是 https://www.javlibrary.com/cn/，拼 base 会得到 .../cn/cn/?v=
    if (raw.startsWith("/")) {
        val origin = originOf(baseUrl)
        return if (origin.isBlank()) raw else origin + raw
    }
    if (base.isBlank()) return raw
    return "$base/$raw"
}

/** 取 scheme + host，用于拼图片域（原实现里 fixImgUrl 干的就是这件事）。 */
fun originOf(url: String): String {
    val m = Regex("""^(https?://[^/\s]+)""").find(url.trim()) ?: return ""
    return m.groupValues[1]
}

/** 从 URL 里取查询参数，例如 javlibrary 的 ?v=ABC-123。 */
fun queryParamOf(url: String, key: String): String? {
    val q = url.substringAfter('?', "")
    if (q.isBlank()) return null
    return q.split('&')
        .map { it.split('=') }
        .firstOrNull { it.size == 2 && it[0] == key }
        ?.get(1)
        ?.takeIf { it.isNotBlank() }
}

/** 清洗文本：去 &nbsp;、合并空白、去首尾空格。 */
fun cleanText(text: String?): String =
    text.orEmpty()
        .replace('\u00A0', ' ')
        .replace('\u3000', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

/** 正则取第一个分组。站点 ID / 番号提取全靠它。 */
fun extractGroup(text: String, pattern: Regex, group: Int = 1): String? =
    pattern.find(text)?.groupValues?.getOrNull(group)?.takeIf { it.isNotBlank() }

/** 取第一个非空字符串（多字段兜底时用）。 */
fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

/** 去掉“第 N 页 - 站点名”这类标题噪音。 */
fun stripSiteSuffix(title: String, vararg suffixes: String): String {
    var t = cleanText(title)
    suffixes.forEach { s -> t = t.replace(s, "", ignoreCase = true) }
    return t.trim(' ', '-', '|', '—')
}

/**
 * XChina 详情：`og:title` / `<title>` 常为「片名 - 分类 - 小黄书 xChina」，
 * 页面正文 `h1` 才是纯片名；无 h1 时取第一个「 - 」段之前的文字。
 */
fun stripXChinaDetailTitle(raw: String): String {
    val t = cleanText(raw)
    if (t.isBlank()) return t
    val siteTail = listOf("xchina", "小黄书", "西范", "China").any { t.contains(it, ignoreCase = true) }
    val segments = t.split(Regex("""\s*[-–—|]\s*"""))
    if (segments.size >= 2 && (segments.size >= 3 || siteTail)) {
        return segments.first().trim()
    }
    return stripSiteSuffix(t, "XChina", "xChina", "小黄书", "西范", "China")
}
