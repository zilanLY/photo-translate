package com.example.phototranslate.domain

/**
 * OCR 文本块的阅读顺序排序。
 *
 * 旧实现仅按 (top, left) 排序，对多栏版面（报纸、双语菜单）会把不同栏的同行文本交错，
 * 对竖排 CJK（招牌、标签）会把列序排反。这里按版面结构排序：
 *  - 竖排版面（多数块「高 ≥ 2×宽」且为 CJK 短文本）：按列分组，列从右到左（竖排阅读方向），
 *    列内自上而下；
 *  - 横排版面：按行分组——纵向中心或上缘相近的块归为同一行，行自上而下、行内从左到右。
 *
 * 纯 Kotlin 实现（仅依赖 TextBlock/Rect），可在 JVM 单元测试中直接验证。
 */
object ReadingOrder {

    /**
     * 对文本块按阅读顺序排序。blocks 会被复制处理，不修改原列表。
     */
    fun sort(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size <= 1) return blocks.sortedBy { it.boundingBox.top }
        return if (isVerticalLayout(blocks)) sortByColumns(blocks) else sortByLines(blocks)
    }

    /**
     * 是否竖排版面：竖排特征 = 块显著窄高（高 ≥ 2×宽）。C 端场景竖排基本只出现在 CJK，
     * 但不限定语种——只要版面呈竖排列状就按竖排读。
     */
    internal fun isVerticalLayout(blocks: List<TextBlock>): Boolean {
        val candidates = blocks.filter { b ->
            val w = (b.boundingBox.right - b.boundingBox.left).coerceAtLeast(1)
            val h = (b.boundingBox.bottom - b.boundingBox.top).coerceAtLeast(1)
            h >= w * 2
        }
        return candidates.size * 10 >= blocks.size * 6 // ≥60% 的块呈窄高 → 竖排
    }

    /** 横排：行分组（上缘/纵向中心相近），行内按 left 升序，行间按 top 升序。 */
    internal fun sortByLines(blocks: List<TextBlock>): List<TextBlock> {
        val sorted = blocks.sortedBy { it.boundingBox.top }.toMutableList()
        val lines = mutableListOf<MutableList<TextBlock>>()
        for (b in sorted) {
            val bb = b.boundingBox
            val center = (bb.top + bb.bottom) / 2f
            val line = lines.lastOrNull { l ->
                l.any { ob ->
                    val obb = ob.boundingBox
                    val oCenter = (obb.top + obb.bottom) / 2f
                    val oHeight = (obb.bottom - obb.top).coerceAtLeast(1)
                    val height = (bb.bottom - bb.top).coerceAtLeast(1)
                    // 纵向中心落在对方行带内，或上缘相差小于行高一半，视为同一行
                    center in obb.top.toFloat()..obb.bottom.toFloat() ||
                        oCenter in bb.top.toFloat()..bb.bottom.toFloat() ||
                        Math.abs(bb.top - obb.top) < minOf(height, oHeight) * 0.5f
                }
            }
            if (line != null) line.add(b) else lines.add(mutableListOf(b))
        }
        return lines.flatMap { line -> line.sortedBy { it.boundingBox.left } }
    }

    /** 竖排：列分组（横向中心/左右缘相近），列内按 top 升序，列间按 right 降序（右列先读）。 */
    internal fun sortByColumns(blocks: List<TextBlock>): List<TextBlock> {
        val sorted = blocks.sortedByDescending { it.boundingBox.right }.toMutableList()
        val columns = mutableListOf<MutableList<TextBlock>>()
        for (b in sorted) {
            val bb = b.boundingBox
            val center = (bb.left + bb.right) / 2f
            val column = columns.lastOrNull { c ->
                c.any { ob ->
                    val obb = ob.boundingBox
                    val oCenter = (obb.left + obb.right) / 2f
                    val oWidth = (obb.right - obb.left).coerceAtLeast(1)
                    val width = (bb.right - bb.left).coerceAtLeast(1)
                    center in obb.left.toFloat()..obb.right.toFloat() ||
                        oCenter in bb.left.toFloat()..bb.right.toFloat() ||
                        Math.abs(bb.right - obb.right) < minOf(width, oWidth) * 0.5f
                }
            }
            if (column != null) column.add(b) else columns.add(mutableListOf(b))
        }
        return columns.flatMap { col -> col.sortedBy { it.boundingBox.top } }
    }
}
