package com.nelson.yomukai.data.social

/**
 * The queue of a room (F4.1): what plays after the current episode, from any anime. Pure rules, so the server and the
 * tests agree: who may change it, how many votes skip the current episode, and what comes next.
 */
object RoomQueue {
    /** A queue longer than this is a playlist dump, not a plan for the evening. */
    const val MAX_ITEMS = 50

    /** Items one person may add while others' are still waiting (so nobody fills the queue alone). */
    const val MAX_PER_PERSON = 10

    data class Item(
        val id: Long,
        val sourceId: String,
        val mediaId: String,
        val chapterId: String,
        val title: String,
        val episode: String = "",
        val cover: String = "",
        val addedBy: Long = 0,
        val addedByName: String = ""
    )

    /** Why an item cannot be added, or null when it can. */
    fun refuseAdd(queue: List<Item>, item: Item): String? = when {
        queue.size >= MAX_ITEMS -> "La cola ya tiene $MAX_ITEMS episodios"
        queue.any { it.sourceId == item.sourceId && it.mediaId == item.mediaId && it.chapterId == item.chapterId } -> "Ese episodio ya está en la cola"
        queue.count { it.addedBy == item.addedBy } >= MAX_PER_PERSON -> "Ya tienes $MAX_PER_PERSON episodios en la cola: espera a que pasen"
        else -> null
    }

    /**
     * Whether [user] may reorder or remove [item]: whoever controls the room (host, or everyone when allowed) touches
     * any item; anybody else only what they added.
     */
    fun canEdit(user: Long, item: Item, controls: Boolean): Boolean = controls || item.addedBy == user

    /** [queue] with the item [id] moved to [to] (clamped); unchanged when the id is not there. */
    fun move(queue: List<Item>, id: Long, to: Int): List<Item> {
        val from = queue.indexOfFirst { it.id == id }
        if (from < 0) return queue
        val list = queue.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return list
    }

    /** Votes that skip the current episode: more than half of the people in the room (one alone skips at once). */
    fun votesToSkip(people: Int): Int = people.coerceAtLeast(1) / 2 + 1

    /** Whether [votes] (people's ids) are enough among [present] (only those still in the room count). */
    fun skipPasses(votes: Set<Long>, present: Set<Long>): Boolean = votes.count { it in present } >= votesToSkip(present.size)
}
