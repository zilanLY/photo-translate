package com.example.phototranslate.repository

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.example.phototranslate.domain.LangUtil
import com.example.phototranslate.domain.OcrResult
import com.example.phototranslate.domain.ReadingOrder
import com.example.phototranslate.domain.Rect
import com.example.phototranslate.domain.TextBlock
import com.example.phototranslate.domain.TextRecognitionResult
import com.example.phototranslate.application.PhotoTranslateApp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.Text.TextBlock as MlKitTextBlock
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Default implementation of OcrRepository using ML Kit Text Recognition.
 *
 * 同时运行「拉丁识别器」与「中日韩(CJK)识别器」并合并结果：
 *  - 拉丁识别器对英文/法文等拉丁字母最准；
 *  - CJK 识别器覆盖中文/日文/韩文（也能读拉丁，但精度低于前者）。
 * 合并策略：完整保留 CJK 识别到的文本块，再补入不与任何 CJK 块重叠的拉丁块，
 * 既避免重复、又让每种文字都走最擅长它的识别器，从而显著提升翻译质量。
 */
class DefaultOcrRepository(
    private val latinClient: TextRecognizer,
    private val cjkClient: TextRecognizer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : OcrRepository {

    override fun analyze(bitmap: Bitmap): OcrResult {
        return try {
            // 两份 InputImage 各自包裹同一 Bitmap；识别器处理的是 InputImage 而非 Bitmap 本身。
            val latinImage = InputImage.fromBitmap(bitmap, 0)
            val cjkImage = InputImage.fromBitmap(bitmap, 0)
            val (blocks, dominant) = recognizeBoth(latinImage, cjkImage)
            OcrResult(
                success = true,
                textRecognitionResult = TextRecognitionResult(
                    blocks = blocks,
                    fullText = joinFullText(blocks, dominant),
                    dominantLanguage = dominant
                )
            )
        } catch (t: Throwable) {
            handleOcrError(t)
        }
    }

    override fun analyze(imageProxy: ImageProxy): OcrResult {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return OcrResult(success = false, errorMessage = "ImageProxy.image is null")
        }
        return try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val latinImage = InputImage.fromMediaImage(mediaImage, rotation)
            val cjkImage = InputImage.fromMediaImage(mediaImage, rotation)
            val (blocks, dominant) = recognizeBoth(latinImage, cjkImage)
            OcrResult(
                success = true,
                textRecognitionResult = TextRecognitionResult(
                    blocks = blocks,
                    fullText = joinFullText(blocks, dominant),
                    dominantLanguage = dominant
                )
            )
        } catch (t: Throwable) {
            handleOcrError(t)
        } finally {
            imageProxy.close() // Always close the proxy after processing
        }
    }

    /**
     * 同时跑两个识别器（并行，缩短实时识别耗时），各自按脚本过滤误识后合并，
     * 并推断占主导的语种。
     */
    private fun recognizeBoth(latinImage: InputImage, cjkImage: InputImage): Pair<List<TextBlock>, String?> {
        // 并行执行两个识别器：墙钟时间取较慢者而非两者之和，实时模式下约快一倍。
        val (latinResult, cjkResult) = runBlocking(Dispatchers.Default) {
            val a = async { Tasks.await(latinClient.process(latinImage)) }
            val b = async { Tasks.await(cjkClient.process(cjkImage)) }
            a.await() to b.await()
        }
        // 各自按脚本过滤：丢识别器在对方文字上的误识（如拉丁识别器在中文上读出的乱码字母），
        // 并丢弃纯标点/纯符号噪声块。
        val latinBlocks = latinResult.textBlocks.map { createTextBlock(it) }.filter { looksLatin(it.text) }
        val cjkBlocks = cjkResult.textBlocks.map { createTextBlock(it) }.filter { looksCjk(it.text) }
        val dominant = dominantLanguage(cjkBlocks + latinBlocks)
        val merged = mergeBlocks(cjkBlocks, latinBlocks, dominant)
        return merged to dominant
    }

    /**
     * 拼接全文：横排拉丁文本用空格分隔（保词界），CJK 文本用换行分隔——
     * 中文/日文词间本无空格，旧实现用空格拼接会人为打断句子、破坏翻译连贯性
     * （用户反馈「语句不通顺」的原因之一）；换行保留块边界，翻译模型按行处理更稳。
     */
    private fun joinFullText(blocks: List<TextBlock>, dominant: String?): String {
        if (blocks.isEmpty()) return ""
        val separator = if (LangUtil.isCjkLanguage(dominant)) "\n" else " "
        return blocks.joinToString(separator) { it.text.trim() }
    }

    /**
     * 合并两个识别器的文本块：
     *  - 以占主导的语种识别器结果为底（CJK 文档用 CJK 块、拉丁文档用拉丁块）；
     *  - 仅补入不与任何底块重叠的「少数语种」块，从而既保留混排内容（如中英并列），
     *    又剔除同区域被主导语种覆盖的误识（如中文上被拉丁识别器读出的乱码）。
     * 最后按版面阅读顺序排序（横排行分组/竖排列分组，见 ReadingOrder），保证翻译输入连贯、语句通顺。
     */
    private fun mergeBlocks(cjk: List<TextBlock>, latin: List<TextBlock>, dominant: String?): List<TextBlock> {
        if (cjk.isEmpty()) return ReadingOrder.sort(latin)
        if (latin.isEmpty()) return ReadingOrder.sort(cjk)
        val preferCjk = LangUtil.isCjkLanguage(dominant)
        val base = if (preferCjk) cjk else latin
        val other = if (preferCjk) latin else cjk
        val result = base.toMutableList()
        for (o in other) {
            val ob = o.boundingBox
            // 空框无法判断重叠，直接保留。
            if (ob.left == 0 && ob.top == 0 && ob.right == 0 && ob.bottom == 0) {
                result.add(o)
                continue
            }
            if (base.none { overlaps(ob, it.boundingBox, 0.25f) }) result.add(o)
        }
        return ReadingOrder.sort(result)
    }

    /**
     * 按各文本块识别语种、并以字符数加权，推断整图主导语种。
     * ML Kit 返回 BCP-47（如 zh-Hans），此处归一化为主语言码（zh），
     * 后续翻译、UI 展示与历史记录均使用统一形态。
     */
    private fun dominantLanguage(blocks: List<TextBlock>): String? {
        val weight = mutableMapOf<String, Int>()
        for (b in blocks) {
            val lang = b.language
            if (lang.isNullOrBlank() || lang == "und") continue
            val normalized = LangUtil.normalize(lang) ?: continue
            weight[normalized] = weight.getOrDefault(normalized, 0) + b.text.length
        }
        return weight.maxByOrNull { it.value }?.key
    }

    /**
     * 字符是否为「实体」中日韩文字（汉字/假名/谚文）。全角标点不算——
     * 否则标点噪声块会通过 looksCjk 过滤混进译文。
     */
    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x3040..0x30FF ||      //  平假名 / 片假名
               code in 0x3400..0x9FFF ||      //  中日韩统一表意文字
               code in 0xF900..0xFAFF ||      //  CJK 兼容汉字
               code in 0xAC00..0xD7AF ||      //  谚文音节
               code in 0x31F0..0x31FF         //  片假名拼音扩展
    }

    /**
     * 文本是否像 CJK：含至少一个 CJK 区段字符。用于滤掉 CJK 识别器在纯拉丁文字上的误识。
     */
    private fun looksCjk(s: String): Boolean = s.any { isCjk(it) }

    /**
     * 文本是否像拉丁文：至少 2 个字母、且不含任何 CJK 字符。
     * 用于滤掉拉丁识别器在 CJK 文字上读出的乱码字母块。
     */
    private fun looksLatin(s: String): Boolean {
        if (s.isBlank()) return false
        if (s.count { it.isLetter() } < 2) return false
        return s.none { isCjk(it) }
    }

    /**
     * 两个矩形交叠比例（交集面积 / 较小矩形面积）是否超过阈值。
     */
    private fun overlaps(a: Rect, b: Rect, threshold: Float): Boolean {
        val ix = maxOf(a.left, b.left)
        val iy = maxOf(a.top, b.top)
        val ax = minOf(a.right, b.right)
        val ay = minOf(a.bottom, b.bottom)
        val iw = (ax - ix).toFloat()
        val ih = (ay - iy).toFloat()
        if (iw <= 0 || ih <= 0) return false
        val inter = iw * ih
        val areaA = maxOf(1f, ((a.right - a.left) * (a.bottom - a.top)).toFloat())
        val areaB = maxOf(1f, ((b.right - b.left) * (b.bottom - b.top)).toFloat())
        val small = minOf(areaA, areaB)
        return inter / small > threshold
    }

    private fun createTextBlock(mlKitBlock: MlKitTextBlock): TextBlock {
        val boundingBox = mlKitBlock.boundingBox
        return TextBlock(
            text = mlKitBlock.text,
            boundingBox = Rect(
                left = boundingBox?.left ?: 0,
                top = boundingBox?.top ?: 0,
                right = boundingBox?.right ?: 0,
                bottom = boundingBox?.bottom ?: 0
            ),
            language = mlKitBlock.recognizedLanguage,
            confidence = 1.0f
        )
    }

    override fun identifyLanguage(text: String): String? {
        // 源语言改由 OCR 块的 recognizedLanguage 推断（见 dominantLanguage），不再依赖此接口。
        return null
    }

    override fun getConfig(): Map<String, Any> = mapOf(
        "model_type" to "ON_DEVICE",
        "performance_mode" to "FAST",
        "language_detection" to true,
        "version" to "16.0.0",
        "recognizers" to "latin+cjk"
    )

    override fun shutdown() {
        // ML Kit 客户端按需复用，无需显式关闭。
    }

    private fun handleOcrError(exception: Throwable): OcrResult {
        return OcrResult(
            success = false,
            errorMessage = "OCR error: ${exception.message}",
            exception = if (exception is Exception) exception else RuntimeException(exception)
        )
    }
}
