package com.opennovel.reader.tts

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Script model used for recognition; manga/manhwa/manhua need different ones. */
enum class OcrScript { LATIN, JAPANESE, KOREAN, CHINESE }

/**
 * Turns manga page images into readable text so text-to-speech can narrate them.
 *
 * Manga chapters are images, so unlike novels there is no source-provided text to
 * speak. Pages are downloaded and run through ML Kit on-device (no network, no
 * per-page cost, works offline once the model is present).
 *
 * Recognition order matters: ML Kit returns text blocks in detection order, which
 * for right-to-left manga is not reading order. Blocks are therefore re-sorted —
 * right-to-left by column then top-to-bottom — for Japanese, and the usual
 * left-to-right for everything else.
 */
class MangaPageOcr(private val client: OkHttpClient) {

    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()

    private fun recognizer(script: OcrScript): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
    }

    /** OCRs one page URL into text lines. Failures yield an empty list. */
    suspend fun readPage(url: String, script: OcrScript): List<String> = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.bytes() }
        }.getOrNull() ?: return@withContext emptyList()

        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return@withContext emptyList()

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer(script).processImageAwait(image)
            orderBlocks(result, script)
        } catch (t: Throwable) {
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    /** OCRs every page of a chapter, in order, into speakable paragraphs. */
    suspend fun readChapter(pageUrls: List<String>, script: OcrScript): List<String> =
        pageUrls.flatMap { readPage(it, script) }.filter { it.isNotBlank() }

    private fun orderBlocks(
        text: com.google.mlkit.vision.text.Text,
        script: OcrScript,
    ): List<String> {
        val blocks = text.textBlocks.filter { it.text.isNotBlank() }
        val sorted = when (script) {
            // Japanese manga reads right-to-left: order by column descending, then down.
            OcrScript.JAPANESE -> blocks.sortedWith(
                compareByDescending<com.google.mlkit.vision.text.Text.TextBlock> {
                    it.boundingBox?.right ?: 0
                }.thenBy { it.boundingBox?.top ?: 0 },
            )
            else -> blocks.sortedWith(
                compareBy<com.google.mlkit.vision.text.Text.TextBlock> { it.boundingBox?.top ?: 0 }
                    .thenBy { it.boundingBox?.left ?: 0 },
            )
        }
        return sorted.map { it.text.replace('\n', ' ').trim() }
    }

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}

/** Bridges ML Kit's Task API to coroutines. */
private suspend fun TextRecognizer.processImageAwait(
    image: InputImage,
): com.google.mlkit.vision.text.Text = suspendCancellableCoroutine { cont ->
    process(image)
        .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
