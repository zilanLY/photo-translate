package com.example.phototranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：BCP-47 → 翻译语言码归一化。
 *
 * 旧实现的灾难链路：ML Kit OCR 返回 "zh-Hans"，翻译仓库查表无此码，
 * 静默回退成英文 → 中文原文被当英文翻译，输出乱码。归一化后必须得到 "zh"。
 */
class LangUtilTest {

    @Test
    fun normalize_bcp47Variants_toPrimaryCode() {
        assertEquals("zh", LangUtil.normalize("zh-Hans"))
        assertEquals("zh", LangUtil.normalize("zh-Hant"))
        assertEquals("zh", LangUtil.normalize("zh-Hant-HK"))
        assertEquals("en", LangUtil.normalize("en"))
        assertEquals("en", LangUtil.normalize("en-US"))
        assertEquals("ja", LangUtil.normalize("ja"))
        assertEquals("ko", LangUtil.normalize("ko"))
    }

    @Test
    fun normalize_caseInsensitive_andTrims() {
        assertEquals("zh", LangUtil.normalize("ZH-HANS"))
        assertEquals("en", LangUtil.normalize(" EN "))
    }

    @Test
    fun normalize_legacyIsoCodes() {
        assertEquals("he", LangUtil.normalize("iw"))
        assertEquals("id", LangUtil.normalize("in"))
    }

    @Test
    fun normalize_iso3Codes() {
        assertEquals("zh", LangUtil.normalize("zho"))
        assertEquals("en", LangUtil.normalize("eng"))
        assertEquals("ja", LangUtil.normalize("jpn"))
    }

    @Test
    fun normalize_specialValues_returnNull() {
        assertNull(LangUtil.normalize("auto"))
        assertNull(LangUtil.normalize("und"))
        assertNull(LangUtil.normalize(null))
        assertNull(LangUtil.normalize(""))
        assertNull(LangUtil.normalize("  "))
    }

    @Test
    fun isCjkLanguage_acceptsVariants() {
        assertTrue(LangUtil.isCjkLanguage("zh-Hans"))
        assertTrue(LangUtil.isCjkLanguage("ja"))
        assertTrue(LangUtil.isCjkLanguage("ko"))
        assertFalse(LangUtil.isCjkLanguage("en-US"))
        assertFalse(LangUtil.isCjkLanguage(null))
        assertFalse(LangUtil.isCjkLanguage("auto"))
    }
}
