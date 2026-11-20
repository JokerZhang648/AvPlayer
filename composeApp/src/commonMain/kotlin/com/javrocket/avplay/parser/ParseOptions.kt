package com.javrocket.avplay.parser

/**
 * 解析选项 —— 对应反编译里的 parser/ParseOptions。
 *
 * baseUrl 必须带进解析器：HTML 里的链接/图片几乎都是相对路径，
 * 解析阶段就要转成绝对地址，否则下游（尤其是图片与播放）会拼错域。
 */
data class ParseOptions(
    val baseUrl: String = "",
    val page: Int = 1,
    /** 站点的番号/ID 前缀等站点特有标记，解析器可选使用。 */
    val sitePrefix: String = "",
) {
    companion object {
        val DEFAULT = ParseOptions()
    }
}
