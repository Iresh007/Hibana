package com.opennovel.reader.tts

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Source language of the text being translated. */
enum class TranslateSource(val label: String, val mlKitCode: String) {
    ENGLISH("English", TranslateLanguage.ENGLISH),
    JAPANESE("Japanese", TranslateLanguage.JAPANESE),
    KOREAN("Korean", TranslateLanguage.KOREAN),
    CHINESE("Chinese", TranslateLanguage.CHINESE),
}

/**
 * On-device translation (ML Kit) so recognised manga text and novel chapters can
 * be read in English or Hindi.
 *
 * Language packs are **downloaded at runtime**, not bundled — that keeps the APK
 * small, but means the first translation for a pair needs network and a moment
 * to fetch (~30MB per language). Downloads are restricted to Wi-Fi by default.
 * Translators are cached per language pair since creating one is expensive.
 */
class TranslationManager {

    private val translators = mutableMapOf<String, Translator>()

    private fun translator(source: TranslateSource, targetCode: String): Translator {
        val key = "${source.mlKitCode}->$targetCode"
        return translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source.mlKitCode)
                    .setTargetLanguage(targetCode)
                    .build(),
            )
        }
    }

    /**
     * Ensures the model pair is present. Returns false when the download fails
     * (offline, or the user declined Wi-Fi-only conditions), so callers can fall
     * back to untranslated text rather than showing nothing.
     */
    suspend fun ensureModel(
        source: TranslateSource,
        targetCode: String,
        requireWifi: Boolean = true,
    ): Boolean = withContext(Dispatchers.IO) {
        val conditions = DownloadConditions.Builder().apply { if (requireWifi) requireWifi() }.build()
        runCatching {
            translator(source, targetCode).downloadModelIfNeededAwait(conditions)
            true
        }.getOrDefault(false)
    }

    /** Translates lines, preserving order. Any line that fails passes through unchanged. */
    suspend fun translate(
        lines: List<String>,
        source: TranslateSource,
        targetCode: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext lines
        val t = translator(source, targetCode)
        lines.map { line ->
            if (line.isBlank()) line
            else runCatching { t.translateAwait(line) }.getOrDefault(line)
        }
    }

    /** Maps the OCR script onto the matching translation source language. */
    fun sourceFor(script: OcrScript): TranslateSource = when (script) {
        OcrScript.JAPANESE -> TranslateSource.JAPANESE
        OcrScript.KOREAN -> TranslateSource.KOREAN
        OcrScript.CHINESE -> TranslateSource.CHINESE
        OcrScript.LATIN -> TranslateSource.ENGLISH
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}

private suspend fun Translator.downloadModelIfNeededAwait(conditions: DownloadConditions) =
    suspendCancellableCoroutine { cont ->
        downloadModelIfNeeded(conditions)
            .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

private suspend fun Translator.translateAwait(text: String): String =
    suspendCancellableCoroutine { cont ->
        translate(text)
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
