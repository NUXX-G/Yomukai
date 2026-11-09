package com.nelson.yomukai.server

/**
 * Where a site whose domain disappeared usually reappears: the same name with another of the endings such sites
 * move between ("zonatmo.org" → "zonatmo.to", "zonatmo.com"...). Only guesses: the canary keeps one only if the
 * whole source works there.
 */
object DomainGuesses {
    private val ENDINGS = listOf("com", "net", "org", "to", "co", "me", "cc", "io", "tv", "xyz", "lat", "app", "ws", "nu", "li", "info", "mx", "es")

    fun of(host: String): List<String> {
        val bare = host.lowercase().removePrefix("www.")
        val label = bare.substringBeforeLast('.').takeIf { it.isNotBlank() && '.' !in it } ?: return emptyList()
        val current = bare.substringAfterLast('.')
        return ENDINGS.filter { it != current }.map { "$label.$it" }
    }
}
