package com.nelson.yomukai.server

import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/**
 * Sites of manga and anime move to a new domain often. The admin writes "old → new" and every request of the server
 * to the old host (pages, APIs and images) goes to the new one, so the source works again without waiting for an
 * update. Only host names, never addresses: the public-address guard of [ServerHttp] still applies to the new host.
 */
class DomainAliases(private val db: Database, private val http: ServerHttp) {
    data class Alias(val from: String, val to: String, val updatedAt: Long)

    suspend fun load() {
        http.hostAliases = db.tx { query("SELECT old_host, new_host FROM domain_aliases") { getString(1) to getString(2) } }.toMap()
    }

    suspend fun list(): List<Alias> =
        db.tx { query("SELECT old_host, new_host, updated_at FROM domain_aliases ORDER BY old_host") { Alias(getString(1), getString(2), getLong(3)) } }

    suspend fun set(from: String, to: String) {
        val old = host(from)
        val new = host(to)
        require(old != new) { "El dominio nuevo es el mismo que el viejo" }
        db.tx {
            update(
                "INSERT INTO domain_aliases(old_host, new_host, updated_at) VALUES(?,?,?) ON CONFLICT(old_host) DO UPDATE SET new_host = excluded.new_host, updated_at = excluded.updated_at",
                old, new, System.currentTimeMillis()
            )
        }
        load()
    }

    suspend fun remove(from: String) {
        db.tx { update("DELETE FROM domain_aliases WHERE old_host = ?", host(from)) }
        load()
    }

    companion object {
        private val HOST = Regex("^(?=.{4,200}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")

        /** "https://www.Site.com/x" or "site.com" → "www.site.com"; anything that is not a plain host name fails. */
        fun host(raw: String): String {
            val h = raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
            require(HOST.matches(h)) { "Escribe solo el dominio, por ejemplo zonatmo.org" }
            return h
        }
    }
}

fun Route.domainAliasRoutes(s: Services) {
    get("/domains") { call.respond(s.domainAliases.list()) }
    put("/domains") {
        val body = com.google.gson.JsonParser.parseString(call.receiveText().take(2_000)).asJsonObject
        s.domainAliases.set(body.get("from")?.asString.orEmpty(), body.get("to")?.asString.orEmpty())
        call.respond(s.domainAliases.list())
    }
    delete("/domains/{from}") {
        s.domainAliases.remove(call.parameters["from"].orEmpty())
        call.respond(s.domainAliases.list())
    }
}
