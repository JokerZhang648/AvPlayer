package com.javrocket.avplay.parser

/**
 * 解析器接口 —— 对应反编译里的 parser/BaseParser。
 *
 * 统一签名：字符串 HTML → 强类型模型。
 * 站点适配器只负责“给 HTML”，解析细节全在各自的 Parser 实现里，
 * 这样解析器可以脱离网络单独做单元测试（原实现做不到这点）。
 */
interface BaseParser<out T> {
    fun parse(html: String, options: ParseOptions = ParseOptions.DEFAULT): T
}
