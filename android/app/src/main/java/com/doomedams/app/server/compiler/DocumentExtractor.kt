package com.doomedams.app.server.compiler

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Single Extracted Page representation
 */
data class ExtractedPage(
    val pageNumber: Int,
    val text: String,
    val isLowDensityScan: Boolean
)

/**
 * Result of raw document extraction
 */
data class ExtractionResult(
    val fileType: String,
    val pages: List<ExtractedPage>,
    val fullText: String,
    val warnings: List<String>,
    val requiresOcr: Boolean
)

/**
 * Offline Document Extractor for TXT and PDF examination papers.
 * Operates purely on-device without cloud or external services.
 */
object DocumentExtractor {

    private const val LOW_DENSITY_CHAR_THRESHOLD = 40

    fun extractText(fileBytes: ByteArray, fileType: String): ExtractionResult {
        return when (fileType.uppercase()) {
            "TXT" -> extractTxt(fileBytes)
            "PDF" -> extractPdf(fileBytes)
            else -> throw IllegalArgumentException("Unsupported file type: '$fileType'. Supported formats: TXT, PDF")
        }
    }

    private fun extractTxt(fileBytes: ByteArray): ExtractionResult {
        val rawText = String(fileBytes, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val page = ExtractedPage(
            pageNumber = 1,
            text = rawText,
            isLowDensityScan = false
        )

        val warnings = mutableListOf<String>()
        if (rawText.isBlank()) {
            warnings.add("The imported text file is empty or contains only whitespace.")
        }

        return ExtractionResult(
            fileType = "TXT",
            pages = listOf(page),
            fullText = rawText,
            warnings = warnings,
            requiresOcr = false
        )
    }

    private fun extractPdf(fileBytes: ByteArray): ExtractionResult {
        val warnings = mutableListOf<String>()
        val pages = mutableListOf<ExtractedPage>()
        var requiresOcr = false

        try {
            // Using PdfBox-Android PDDocument
            val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(fileBytes))
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()

            val numberOfPages = document.numberOfPages
            for (pageIdx in 1..numberOfPages) {
                stripper.startPage = pageIdx
                stripper.endPage = pageIdx
                val pageText = stripper.getText(document) ?: ""

                val cleanText = pageText.trim()
                val isLowDensity = cleanText.length < LOW_DENSITY_CHAR_THRESHOLD

                if (isLowDensity) {
                    requiresOcr = true
                    warnings.add("Page $pageIdx has very low text density (${cleanText.length} chars). Scanned image suspected; offline OCR is unavailable on this node.")
                }

                pages.add(
                    ExtractedPage(
                        pageNumber = pageIdx,
                        text = pageText,
                        isLowDensityScan = isLowDensity
                    )
                )
            }
            document.close()
        } catch (e: Exception) {
            warnings.add("PDF extraction encounter an issue: ${e.message ?: "Unknown PDF error"}. Falling back to safe string inspection.")
            // Safe fallback if PDF header parsing has an issue
            val fallbackText = String(fileBytes, StandardCharsets.ISO_8859_1)
            pages.add(
                ExtractedPage(
                    pageNumber = 1,
                    text = fallbackText,
                    isLowDensityScan = true
                )
            )
            requiresOcr = true
        }

        val fullText = pages.joinToString("\n\n--- PAGE BREAK ---\n\n") { it.text }
        return ExtractionResult(
            fileType = "PDF",
            pages = pages,
            fullText = fullText,
            warnings = warnings,
            requiresOcr = requiresOcr
        )
    }
}
