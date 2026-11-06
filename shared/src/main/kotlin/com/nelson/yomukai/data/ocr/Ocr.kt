package com.nelson.yomukai.data.ocr

/** A piece of text found on a page. Coordinates are fractions of the page (0..1), so any screen size can use them. */
data class TextBox(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val text: String,
    val kind: BoxKind = BoxKind.BUBBLE,
    val confidence: Double = 1.0
) {
    val cx: Double get() = x + w / 2
    val cy: Double get() = y + h / 2
}

/** Speech (read aloud, translated) or a sound effect (skipped unless the reader asks for them). */
enum class BoxKind { BUBBLE, SFX }

/** How a page is read: manhwa top to bottom, manga right to left, Western comics left to right. */
enum class ReadingDirection { TOP_DOWN, RIGHT_TO_LEFT, LEFT_TO_RIGHT }

/** The OCR of one page, as stored on the server and shared by everyone who reads it. */
data class PageText(val boxes: List<TextBox>, val engine: String, val language: String)

object ReadingOrder {
    /**
     * Boxes in reading order. Manhwa: by height on the page. Manga and comics: in rows (boxes that overlap vertically
     * are one row), rows from top to bottom, and inside a row right to left (manga) or left to right (comics).
     */
    fun sort(boxes: List<TextBox>, direction: ReadingDirection): List<TextBox> {
        if (direction == ReadingDirection.TOP_DOWN) return boxes.sortedWith(compareBy({ it.y }, { it.x }))
        val rows = mutableListOf<MutableList<TextBox>>()
        for (b in boxes.sortedBy { it.y }) {
            val row = rows.lastOrNull()
            if (row != null && row.any { overlap(it, b) > 0.5 }) row += b else rows += mutableListOf(b)
        }
        return rows.flatMap { row -> if (direction == ReadingDirection.RIGHT_TO_LEFT) row.sortedByDescending { it.cx } else row.sortedBy { it.cx } }
    }

    /** How much two boxes share vertically, as a fraction of the shorter one. */
    private fun overlap(a: TextBox, b: TextBox): Double {
        val top = maxOf(a.y, b.y)
        val bottom = minOf(a.y + a.h, b.y + b.h)
        return ((bottom - top) / minOf(a.h, b.h)).coerceAtLeast(0.0)
    }

    /** The usual direction for a kind of work. */
    fun forType(type: String): ReadingDirection = when (type.uppercase()) {
        "MANHWA", "MANHUA" -> ReadingDirection.TOP_DOWN
        "MANGA" -> ReadingDirection.RIGHT_TO_LEFT
        else -> ReadingDirection.LEFT_TO_RIGHT
    }
}

object BubbleClassifier {
    private val LETTERS = Regex("\\p{L}")
    private val REPEAT = Regex("(\\p{L})\\1{2,}")

    /**
     * Sound effects are short, shouted words without a sentence around them ("BOOM", "Krrr", "¡¡ZAS!!"), often drawn big.
     * Anything with two or more words, or that reads like a sentence, is speech. [pageHeight] lets very tall lettering
     * (more than 6% of the page for one line) count as drawn sound even when it is a real word.
     */
    fun classify(text: String, boxHeight: Double = 0.0, lines: Int = 1): BoxKind {
        val clean = text.trim()
        val words = clean.split(Regex("\\s+")).filter { LETTERS.containsMatchIn(it) }
        if (words.isEmpty()) return BoxKind.SFX
        if (words.size >= 3) return BoxKind.BUBBLE
        val letters = clean.filter { it.isLetter() }
        val shouted = letters.isNotEmpty() && letters.all { !it.isLowerCase() }
        val stretched = REPEAT.containsMatchIn(clean.lowercase())
        val huge = lines > 0 && boxHeight / lines > HUGE_LINE
        return when {
            words.size == 1 && (stretched || huge || (shouted && letters.length <= 6)) -> BoxKind.SFX
            words.size == 2 && stretched && shouted -> BoxKind.SFX
            else -> BoxKind.BUBBLE
        }
    }

    private const val HUGE_LINE = 0.06
}

/**
 * Tesseract's TSV output (`tesseract page.png - tsv`) as boxes: words joined into their paragraph, coordinates turned
 * into fractions of the page, empty or low-confidence results dropped, each box classified.
 */
object TesseractTsv {
    fun parse(tsv: String, minConfidence: Double = 40.0): List<TextBox> {
        val lines = tsv.lineSequence().drop(1).map { it.split('\t') }.filter { it.size >= 12 }.toList()
        val page = lines.firstOrNull { it[0] == "1" } ?: return emptyList()
        val pw = page[8].toDoubleOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val ph = page[9].toDoubleOrNull()?.takeIf { it > 0 } ?: return emptyList()
        data class Word(val key: String, val line: String, val left: Double, val top: Double, val w: Double, val h: Double, val conf: Double, val text: String)
        val words = lines.filter { it[0] == "5" }.mapNotNull { c ->
            val text = c[11].trim()
            val conf = c[10].toDoubleOrNull() ?: return@mapNotNull null
            if (text.isEmpty() || conf < minConfidence) return@mapNotNull null
            Word("${c[2]}-${c[3]}", "${c[2]}-${c[3]}-${c[4]}", c[6].toDouble(), c[7].toDouble(), c[8].toDouble(), c[9].toDouble(), conf, text)
        }
        return words.groupBy { it.key }.values.map { ws ->
            val left = ws.minOf { it.left }
            val top = ws.minOf { it.top }
            val right = ws.maxOf { it.left + it.w }
            val bottom = ws.maxOf { it.top + it.h }
            val text = ws.groupBy { it.line }.values.joinToString(" ") { line -> line.joinToString(" ") { it.text } }
            val lineCount = ws.map { it.line }.distinct().size
            val box = TextBox(left / pw, top / ph, (right - left) / pw, (bottom - top) / ph, text, confidence = ws.map { it.conf }.average() / 100)
            box.copy(kind = BubbleClassifier.classify(text, box.h, lineCount))
        }
    }
}
