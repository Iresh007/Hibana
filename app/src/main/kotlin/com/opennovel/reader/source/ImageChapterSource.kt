package com.opennovel.reader.source

/**
 * Implemented by sources whose chapters are **images** rather than text — i.e.
 * Mihon/Manatan manga sources.
 *
 * Hibana's [Source] contract returns chapter *text*, which such sources cannot
 * provide directly. Exposing page image URLs separately lets the reader display
 * them and lets text-to-speech OCR them, without forcing every text source to
 * know about pages.
 */
interface ImageChapterSource {
    /** Ordered page image URLs for a chapter. */
    suspend fun getPageUrls(chapterUrl: String): List<String>
}
