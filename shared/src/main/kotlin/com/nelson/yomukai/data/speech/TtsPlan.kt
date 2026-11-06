package com.nelson.yomukai.data.speech

/**
 * Where a voice comes from, best first: the home worker (high quality), the device itself (sherpa-onnx in the app,
 * Kokoro/Supertonic in the browser), Piper on the VPS (light last resort) and the system's own voice (always there).
 */
enum class TtsTier { WORKER, DEVICE, SERVER, SYSTEM }

object TtsPlan {
    /**
     * The tiers to try, in order, given what is available right now. [offline] puts the device first (no network);
     * the system voice is always last so reading never simply stops.
     */
    fun order(available: Set<TtsTier>, offline: Boolean = false): List<TtsTier> {
        val preferred = if (offline) listOf(TtsTier.DEVICE, TtsTier.SYSTEM) else listOf(TtsTier.WORKER, TtsTier.DEVICE, TtsTier.SERVER, TtsTier.SYSTEM)
        return preferred.filter { it in available || it == TtsTier.SYSTEM }
    }

    /**
     * A chapter as pieces worth one synthesis each: whole sentences, at most [max] characters, never cut inside a
     * word. The same text always gives the same pieces, so audio made for one person is reused for the next.
     */
    fun chunks(text: String, max: Int = 400): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split(Regex("\\n\\s*\\n|\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }) {
            val sentences = SENTENCE.findAll(paragraph).map { it.value.trim() }.filter { it.isNotEmpty() }.toList().ifEmpty { listOf(paragraph) }
            val current = StringBuilder()
            for (s in sentences) {
                if (s.length > max) {
                    if (current.isNotEmpty()) { out += current.toString(); current.clear() }
                    out += splitLong(s, max)
                    continue
                }
                if (current.isNotEmpty() && current.length + 1 + s.length > max) { out += current.toString(); current.clear() }
                if (current.isNotEmpty()) current.append(' ')
                current.append(s)
            }
            if (current.isNotEmpty()) out += current.toString()
        }
        return out
    }

    private val SENTENCE = Regex("""[^.!?…。！？]+[.!?…。！？]*["»”')\]]*""")

    private fun splitLong(sentence: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (word in sentence.split(' ')) {
            if (current.isNotEmpty() && current.length + 1 + word.length > max) { out += current.toString(); current.clear() }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word.take(max))
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
