package com.example.phototranslate.domain

/**
 * 语言码工具：把 ML Kit 各处返回的 BCP-47 码（如 OCR 的 "zh-Hans"、语言识别的 "zh-Hant"）
 * 归一化为主语言码（"zh"），供 ML Kit Translate 使用。
 *
 * 根因背景：ML Kit OCR 的 recognizedLanguage 返回 BCP-47（zh-Hans / zh-Hant / zh-Hant-HK 等），
 * 而 TranslateLanguage 只认主语言码（zh / ja / ko / en …）。旧实现把不认识的码静默回退成英文，
 * 导致中文原文被当英文翻译、输出乱码（用户反馈「翻译错误率高、语句不通顺」的主因）。
 * 这里只做纯文本归一化；「该码是否受支持」由调用方用 TranslateLanguage.getAllLanguages()
 * 校验，不支持时探测或报错，绝不再静默当英文处理。
 */
object LangUtil {

    /** 旧式 ISO 639 遗留码 → 现行码。 */
    private val legacyAliases = mapOf(
        "iw" to "he",  // Hebrew 旧码
        "in" to "id",  // Indonesian 旧码
        "ji" to "yi",  // Yiddish 旧码
    )

    /** 3 字母 ISO 639-2/T → 2 字母 ISO 639-1（语言识别偶发返回 3 字母码）。 */
    private val iso3ToIso1 = mapOf(
        "zho" to "zh", "chi" to "zh", "eng" to "en", "jpn" to "ja", "kor" to "ko",
        "fra" to "fr", "fre" to "fr", "deu" to "de", "ger" to "de", "spa" to "es",
        "rus" to "ru", "por" to "pt", "ita" to "it", "nld" to "nl", "dut" to "nl",
        "pol" to "pl", "tur" to "tr", "tha" to "th", "hin" to "hi", "ara" to "ar",
        "vie" to "vi", "ind" to "id", "fas" to "fa", "per" to "fa", "ces" to "cs",
        "cze" to "cs", "ell" to "el", "gre" to "el", "ron" to "ro", "rum" to "ro",
        "swe" to "sv", "dan" to "da", "fin" to "fi", "heb" to "he",
    )

    /**
     * BCP-47 → 主语言码。如 "zh-Hant-HK"→"zh"、"en"→"en"、"in"→"id"。
     * "auto"/"und"/空白返回 null。不做支持性校验（由调用方按需校验）。
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val code = raw.trim()
        if (code.equals("auto", ignoreCase = true) || code.equals("und", ignoreCase = true)) return null

        var primary = code.substringBefore('-').lowercase()
        legacyAliases[primary]?.let { return it }
        iso3ToIso1[primary]?.let { return it }
        return primary
    }

    /** 是否中日韩语种（阅读顺序/拼接策略判断用），接受 BCP-47 变体如 "zh-Hans"。 */
    fun isCjkLanguage(code: String?): Boolean {
        val primary = normalize(code) ?: return false
        return primary == "zh" || primary == "ja" || primary == "ko"
    }
}
