package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.nelson.yomukai.data.learning.Grade
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DecksTest {
    private val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykdecks").toString(), "BACKUPS_ENABLED" to "false")))
    private val ana = runBlocking { s.db.createUser("ana", "h", admin = false, guest = false, expiresAt = null) }
    private val leo = runBlocking { s.db.createUser("leo", "h", admin = false, guest = false, expiresAt = null) }

    private suspend fun save(user: User, word: String, lang: String = "en") =
        s.words.save(user, JsonObject().apply { addProperty("word", word); addProperty("lang", lang); addProperty("meaning", "m-$word") })

    @Test
    fun sharedDecksAreCopiedWithoutDuplicates() = runBlocking {
        save(ana, "apple"); save(ana, "river"); save(ana, "苹果", "zh")
        val id = s.words.publishDeck(ana, "Frutas", "en")
        val listed = s.words.decks(leo).single()
        assertEquals(listOf("ana", "Frutas", "en", 2), listOf(listed.owner, listed.name, listed.lang, listed.size))
        assertTrue(!listed.mine && s.words.decks(ana).single().mine)
        save(leo, "river")
        assertEquals(1, s.words.importDeck(leo, id))
        assertEquals(setOf("apple", "river"), s.words.list(leo, false, "en").map { it.word }.toSet())
        // Someone who hides their profile: their decks can't be listed or copied by guessing the id.
        s.db.tx { update("UPDATE users SET hidden = 1 WHERE id = ?", ana.id) }
        assertTrue(s.words.decks(leo).isEmpty())
        assertTrue(runCatching { s.words.importDeck(leo, id) }.exceptionOrNull() is NoSuchElementException)
        s.db.tx { update("UPDATE users SET hidden = 0 WHERE id = ?", ana.id) }
        // Only its owner (or an admin) removes it.
        s.words.deleteDeck(leo, id)
        assertEquals(1, s.words.decks(ana).size)
        s.words.deleteDeck(ana, id)
        assertTrue(s.words.decks(ana).isEmpty())
    }

    @Test
    fun weeklyRankingCountsReviews() = runBlocking {
        val a = save(ana, "apple")
        val b = save(leo, "river")
        repeat(3) { s.words.review(ana, a, Grade.GOOD) }
        s.words.review(leo, b, Grade.AGAIN)
        assertEquals(listOf("ana" to 3, "leo" to 1), s.words.ranking().map { it["name"] to it["reviews"] })
    }
}
