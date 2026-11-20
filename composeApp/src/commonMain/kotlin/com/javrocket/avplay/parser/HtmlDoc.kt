package com.javrocket.avplay.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/**
 * HTML 解析的唯一第三方入口 —— 全工程只有这一个文件 import Ksoup。
 *
 * 为什么要包一层：Ksoup（jsoup 的 KMP 移植）在不同大版本上 API/坐标会有调整，
 * 把它收在 30 行里，将来换库或升版本只改这一处，14 个站点的 Parser 都不用动。
 */
object Html {
    fun parse(html: String): HtmlDoc = HtmlDoc(Ksoup.parse(html))
}

class HtmlDoc internal constructor(private val doc: Document) {
    fun select(selector: String): List<HtmlNode> = doc.select(selector).map { HtmlNode(it) }

    fun selectFirst(selector: String): HtmlNode? = doc.selectFirst(selector)?.let { HtmlNode(it) }

    val title: String get() = doc.title()

    val text: String get() = doc.text()

    val html: String get() = doc.outerHtml()
}

class HtmlNode internal constructor(private val el: Element) {
    fun select(selector: String): List<HtmlNode> = el.select(selector).map { HtmlNode(it) }

    fun selectFirst(selector: String): HtmlNode? = el.selectFirst(selector)?.let { HtmlNode(it) }

    fun attr(name: String): String = el.attr(name)

    fun hasAttr(name: String): Boolean = el.hasAttr(name)

    val text: String get() = el.text()

    val html: String get() = el.outerHtml()
}
