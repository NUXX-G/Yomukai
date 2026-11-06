package com.nelson.yomukai.data.media

/**
 * Names and metadata files for media servers, as pure functions: Jellyfin's folder layout and .nfo files for episodes,
 * and ComicInfo.xml for Kavita/Komga. The worker writes them; nothing here touches the disk.
 */
object JellyfinNaming {
    private val ILLEGAL = Regex("""[<>:"/\\|?*\u0000-\u001F]""")

    /** A name safe on Windows, Linux and macOS: no reserved characters, no trailing dots or spaces, not too long. */
    fun safe(name: String): String {
        val clean = name.replace(ILLEGAL, " ").replace(Regex("\\.{2,}"), ".").replace(Regex("\\s+"), " ").trim()
            .trimStart('.', ' ').trimEnd('.', ' ').take(120)
        return clean.ifBlank { "Sin título" }.let { if (it.uppercase() in RESERVED) "_$it" else it }
    }

    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /** "Frieren (2023)" */
    fun seriesFolder(title: String, year: Int?): String = safe(if (year != null && year > 1900) "$title ($year)" else title)

    fun seasonFolder(season: Int): String = "Season %02d".format(season.coerceAtLeast(0))

    /** "Frieren (2023)/Season 01/Frieren - S01E05.mkv" (a part after the dot, e.g. 12.5, becomes S01E12.5 → "S01E12-5"). */
    fun episodePath(title: String, year: Int?, season: Int, episode: Double, extension: String = "mkv"): String {
        val number = if (episode % 1.0 == 0.0) "%02d".format(episode.toInt()) else "%02d-%d".format(episode.toInt(), Math.round((episode % 1) * 10).toInt())
        val file = "${safe(title)} - S%02dE%s.%s".format(season.coerceAtLeast(0), number, extension.trimStart('.').lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mkv" })
        return "${seriesFolder(title, year)}/${seasonFolder(season)}/$file"
    }

    /** "Película (2021)/Película (2021).mkv" */
    fun moviePath(title: String, year: Int?, extension: String = "mkv"): String {
        val folder = seriesFolder(title, year)
        return "$folder/$folder.${extension.trimStart('.').lowercase().filter { it.isLetterOrDigit() }.ifBlank { "mkv" }}"
    }
}

/** Known ids of a work in other databases, written into the metadata so media servers match it right. */
data class ExternalIds(val anilist: Int? = null, val mal: Int? = null, val tvdb: Int? = null)

object Nfo {
    fun escape(text: String): String = buildString {
        for (c in text) {
            when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;")
            c == '"' -> append("&quot;"); c == '\'' -> append("&apos;")
            c == '\n' || c == '\t' || c.code >= 0x20 -> append(c)
        }
        }
    }

    private fun ids(ids: ExternalIds) = buildString {
        ids.anilist?.let { append("  <uniqueid type=\"anilist\">$it</uniqueid>\n") }
        ids.mal?.let { append("  <uniqueid type=\"mal\">$it</uniqueid>\n") }
        ids.tvdb?.let { append("  <uniqueid type=\"tvdb\" default=\"true\">$it</uniqueid>\n") }
    }

    fun tvshow(title: String, year: Int?, plot: String, genres: List<String>, ids: ExternalIds): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<tvshow>\n")
        append("  <title>${escape(title)}</title>\n")
        year?.let { append("  <year>$it</year>\n") }
        if (plot.isNotBlank()) append("  <plot>${escape(plot)}</plot>\n")
        genres.forEach { append("  <genre>${escape(it)}</genre>\n") }
        append(ids(ids))
        append("</tvshow>\n")
    }

    fun season(number: Int): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<season>\n  <seasonnumber>$number</seasonnumber>\n  <title>Temporada $number</title>\n</season>\n"

    fun episode(showTitle: String, title: String, season: Int, episode: Double, ids: ExternalIds): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<episodedetails>\n")
        append("  <title>${escape(title.ifBlank { "Episodio ${formatNumber(episode)}" })}</title>\n")
        append("  <showtitle>${escape(showTitle)}</showtitle>\n")
        append("  <season>$season</season>\n  <episode>${episode.toInt()}</episode>\n")
        append(ids(ids))
        append("</episodedetails>\n")
    }

    fun formatNumber(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
}

/** ComicInfo.xml (the Anansi project format read by Kavita, Komga and most comic readers). */
object ComicInfo {
    data class Info(
        val series: String, val number: String, val title: String = "", val volume: Int? = null, val summary: String = "",
        val writers: List<String> = emptyList(), val genres: List<String> = emptyList(), val year: Int? = null,
        val language: String = "", val manga: Boolean = false, val ids: ExternalIds = ExternalIds(), val pageCount: Int = 0
    )

    fun xml(i: Info): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
        fun tag(name: String, value: String?) { if (!value.isNullOrBlank()) append("  <$name>${Nfo.escape(value)}</$name>\n") }
        tag("Title", i.title)
        tag("Series", i.series)
        tag("Number", i.number)
        tag("Volume", i.volume?.toString())
        tag("Summary", i.summary)
        tag("Year", i.year?.toString())
        tag("Writer", i.writers.joinToString(", "))
        tag("Genre", i.genres.joinToString(", "))
        tag("LanguageISO", i.language)
        if (i.manga) tag("Manga", "YesAndRightToLeft")
        if (i.pageCount > 0) tag("PageCount", i.pageCount.toString())
        val web = listOfNotNull(i.ids.anilist?.let { "https://anilist.co/manga/$it" }, i.ids.mal?.let { "https://myanimelist.net/manga/$it" })
        tag("Web", web.joinToString(" "))
        append("</ComicInfo>\n")
    }

    /** "Series/Series - Ch. 012.cbz" (volume first when known: "Series - Vol. 03 Ch. 012.cbz"). */
    fun cbzPath(series: String, number: Double, volume: Int?): String {
        val n = if (number % 1.0 == 0.0) "%03d".format(number.toInt()) else "%03d.%d".format(number.toInt(), Math.round((number % 1) * 10).toInt())
        val vol = volume?.let { "Vol. %02d ".format(it) }.orEmpty()
        val s = JellyfinNaming.safe(series)
        return "$s/$s - ${vol}Ch. $n.cbz"
    }
}
