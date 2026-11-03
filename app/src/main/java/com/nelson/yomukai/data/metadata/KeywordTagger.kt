package com.nelson.yomukai.data.metadata

import com.nelson.yomukai.data.search.SmartSearch

/**
 * Guesses genres from a synopsis when nobody lists them: plain keyword rules in Spanish and
 * English, no model. A genre needs [MIN_HITS] distinct cues (or one strong cue) so a passing word
 * does not tag a whole work. Pure and cheap: one pass over the words of the text.
 */
object KeywordTagger {
    private class Rule(val genre: String, val strong: List<String>, val weak: List<String>)

    private val RULES = listOf(
        Rule("Isekai", listOf("isekai", "otro mundo", "another world", "reencarn", "reincarnat", "transmigr", "invocado a otro"), listOf("summoned", "invocad")),
        Rule("Artes marciales", listOf("murim", "wuxia", "cultivo", "cultivation", "artes marciales", "martial arts", "secta", "sect"), listOf("maestro", "master", "dantian", "qi")),
        Rule("Juegos", listOf("videojuego", "video game", "vrmmo", "mmorpg", "level up", "mazmorra", "dungeon", "status window"), listOf("jugador", "player", "nivel", "sistema", "system", "quest", "mision")),
        Rule("Romance", listOf("enamor", "fall in love", "falls in love", "romance", "amor", "love story", "prometid", "fiance", "boda", "wedding"), listOf("corazon", "heart", "beso", "kiss", "novio", "novia", "boyfriend", "girlfriend")),
        Rule("Comedia", listOf("comedia", "comedy", "hilarant", "hilarious", "divertid"), listOf("humor", "funny", "risas")),
        Rule("Terror", listOf("terror", "horror", "pesadilla", "nightmare", "monstruo", "monster"), listOf("miedo", "fear", "sangre", "blood")),
        Rule("Misterio", listOf("misterio", "mystery", "detective", "asesinato", "murder", "crimen", "crime"), listOf("pista", "clue", "investig")),
        Rule("Venganza", listOf("venganza", "revenge", "vengar", "avenge", "traicion", "betray"), listOf("odio", "hatred")),
        Rule("Fantasía", listOf("magia", "magic", "dragon", "hechicer", "sorcer", "reino", "kingdom", "elfo", "elf"), listOf("espada", "sword", "caballero", "knight", "mago", "mage", "wizard")),
        Rule("Escolar", listOf("instituto", "high school", "academia", "academy", "escuela", "school", "estudiante", "student"), listOf("clase", "class", "profesor", "teacher")),
        Rule("Apocalíptico", listOf("apocalip", "apocalyp", "fin del mundo", "end of the world", "zombi", "zombie", "supervivencia", "survival"), listOf("ruinas", "ruins")),
        Rule("Ciencia ficción", listOf("nave espacial", "spaceship", "galaxia", "galaxy", "robot", "cyborg", "androide", "android"), listOf("tecnologia", "technology", "planeta", "planet", "futuro", "future")),
        Rule("Deportes", listOf("futbol", "soccer", "football", "baloncesto", "basketball", "voleibol", "volleyball", "beisbol", "baseball", "tenis", "tennis", "boxeo", "boxing"), listOf("equipo", "team", "torneo", "tournament", "partido")),
        Rule("Recuentos de la vida", listOf("vida cotidiana", "everyday life", "slice of life", "dia a dia"), listOf("trabajo", "work", "familia", "family")),
        Rule("Histórico", listOf("dinastia", "dynasty", "emperador", "emperor", "imperio", "empire", "edad media", "medieval", "samurai"), listOf("palacio", "palace", "princes", "corte")),
        Rule("Sobrenatural", listOf("fantasma", "ghost", "espiritu", "spirit", "demonio", "demon", "maldicion", "curse", "vampir"), listOf("poder", "power", "alma", "soul")),
        Rule("Acción", listOf("batalla", "battle", "pelea", "fight", "combate", "combat", "guerra", "war"), listOf("enemigo", "enemy", "arma", "weapon", "matar", "kill")),
        Rule("Drama", listOf("tragedia", "tragedy", "drama"), listOf("lagrimas", "tears", "perdida", "loss", "muerte", "death")),
        Rule("Psicológico", listOf("psicolog", "psycholog", "mente", "mind game", "manipul"), listOf("locura", "madness", "trauma"))
    ).map { rule ->
        Rule(rule.genre, rule.strong.map(SmartSearch::normalize), rule.weak.map(SmartSearch::normalize))
    }

    /**
     * Genres the synopsis hints at, strongest first, at most [limit]. Words match by prefix, so
     * "enamor" catches "enamorado" and "enamoran".
     */
    fun infer(text: String, limit: Int = MAX_TAGS): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = " " + SmartSearch.normalize(text) + " "
        val scored = RULES.mapNotNull { rule ->
            val strong = rule.strong.count { cue -> normalized.contains(" $cue") }
            val weak = rule.weak.count { cue -> normalized.contains(" $cue") }
            val score = strong * STRONG_WEIGHT + weak
            if (strong >= 1 && score >= MIN_HITS || weak >= MIN_WEAK) rule.genre to score else null
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private const val STRONG_WEIGHT = 2
    private const val MIN_HITS = 2
    private const val MIN_WEAK = 3
    private const val MAX_TAGS = 5
}
