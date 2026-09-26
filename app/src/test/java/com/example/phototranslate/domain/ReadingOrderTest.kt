package com.example.phototranslate.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读顺序排序测试：横排行分组 + 竖排列分组。
 * 旧实现仅按 (top, left) 排序，多栏版面会交错、竖排列序会排反。
 */
class ReadingOrderTest {

    private fun block(text: String, left: Int, top: Int, right: Int, bottom: Int) = TextBlock(
        text = text,
        boundingBox = Rect(left, top, right, bottom)
    )

    @Test
    fun sort_singleLine_byLeft() {
        val blocks = listOf(
            block("C", 200, 100, 300, 140),
            block("A", 0, 100, 100, 140),
            block("B", 120, 100, 190, 140),
        )
        assertEquals(listOf("A", "B", "C"), ReadingOrder.sort(blocks).map { it.text })
    }

    @Test
    fun sort_twoLines_topToBottom_withinLineByLeft() {
        val blocks = listOf(
            block("line2-right", 150, 200, 300, 240),
            block("line1-left", 0, 50, 140, 90),
            block("line2-left", 0, 200, 140, 240),
            block("line1-right", 150, 50, 300, 90),
        )
        assertEquals(
            listOf("line1-left", "line1-right", "line2-left", "line2-right"),
            ReadingOrder.sort(blocks).map { it.text }
        )
    }

    @Test
    fun sort_slightlyOffsetBlocks_groupedIntoSameLine() {
        // 多栏报纸：第二列整体比第一列低 15px，纵向中心仍应归入同一行。
        val blocks = listOf(
            block("col2-line1", 300, 65, 500, 105),
            block("col1-line1", 0, 50, 200, 90),
            block("col1-line2", 0, 100, 200, 140),
            block("col2-line2", 300, 115, 500, 155),
        )
        assertEquals(
            listOf("col1-line1", "col2-line1", "col1-line2", "col2-line2"),
            ReadingOrder.sort(blocks).map { it.text }
        )
    }

    @Test
    fun verticalLayout_narrowTallBlocks_detected() {
        val vertical = listOf(
            block("中", 100, 0, 140, 400),
            block("国", 200, 0, 240, 300),
        )
        assertTrue(ReadingOrder.isVerticalLayout(vertical))
    }

    @Test
    fun sort_verticalColumns_rightToLeft_topToBottom() {
        // 竖排招牌：右列「今日」先读，左列「特价」后读，列内自上而下。
        val blocks = listOf(
            block("日", 200, 220, 240, 320),
            block("今", 200, 100, 240, 200),
            block("价", 100, 200, 140, 300),
            block("特", 100, 80, 140, 180),
        )
        assertEquals(
            listOf("今", "日", "特", "价"),
            ReadingOrder.sort(blocks).map { it.text }
        )
    }

    @Test
    fun sort_emptyAndSingle() {
        assertEquals(0, ReadingOrder.sort(emptyList()).size)
        val one = listOf(block("x", 0, 0, 10, 10))
        assertEquals("x", ReadingOrder.sort(one)[0].text)
    }
}
