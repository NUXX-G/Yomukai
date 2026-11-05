package com.nelson.yomukai.server

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Video traffic that goes through the VPS (/api/stream), per calendar month. Counted in memory and written to the
 * database once a minute. With a cap ([capBytes] > 0) the admin is warned at 80 % and 100 %, and at 100 % the proxy
 * stops serving video to non-admins until the month ends (streams the device can play directly keep working).
 */
class Bandwidth(
    private val db: Database,
    private val capBytes: Long,
    private val notifier: Notifier,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val pending = AtomicLong()

    @Volatile private var month = monthOf(clock())

    @Volatile private var stored = 0L

    @Volatile private var loaded = false

    fun record(bytes: Long) {
        if (bytes > 0) pending.addAndGet(bytes)
    }

    /** Bytes this month, including what is not written yet. */
    suspend fun used(): Long {
        ensureMonth()
        return stored + pending.get()
    }

    enum class Level { OK, WARNING, OVER }

    suspend fun level(): Level = levelFor(used(), capBytes)

    /** True when the cap is on and reached: video through the VPS is refused. */
    suspend fun blocked(enforce: Boolean): Boolean = enforce && level() == Level.OVER

    private suspend fun ensureMonth() {
        val now = monthOf(clock())
        if (loaded && now == month) return
        if (loaded && now != month) flush()
        month = now
        stored = db.tx { queryOne("SELECT bytes FROM bandwidth WHERE month = ?", now) { getLong(1) } } ?: 0
        loaded = true
    }

    /** Writes what was counted and sends the warnings due. Run once a minute. */
    suspend fun flush() {
        val target = month
        val delta = pending.getAndSet(0)
        val (total, warned80, warned100) = db.tx {
            update("INSERT INTO bandwidth(month, bytes) VALUES(?, ?) ON CONFLICT(month) DO UPDATE SET bytes = bytes + excluded.bytes", target, delta)
            queryOne("SELECT bytes, warned80, warned100 FROM bandwidth WHERE month = ?", target) { Triple(getLong(1), getInt(2) == 1, getInt(3) == 1) }!!
        }
        if (target == month) stored = total
        if (capBytes <= 0) return
        val level = levelFor(total, capBytes)
        if (level == Level.OVER && !warned100) {
            db.tx { update("UPDATE bandwidth SET warned100 = 1, warned80 = 1 WHERE month = ?", target) }
            notifier.admin(
                "bw100-$target", "Tope de tráfico alcanzado",
                "El vídeo por el VPS ha llegado al tope de ${gb(capBytes)} este mes (${gb(total)}). Se corta hasta el mes que viene salvo para administradores.",
                urgent = true
            )
        } else if (level != Level.OK && !warned80) {
            db.tx { update("UPDATE bandwidth SET warned80 = 1 WHERE month = ?", target) }
            notifier.admin("bw80-$target", "Tráfico al 80 %", "El vídeo por el VPS lleva ${gb(total)} de ${gb(capBytes)} este mes.")
        }
    }

    suspend fun summary(): Map<String, Any> = mapOf("month" to month, "usedBytes" to used(), "capBytes" to capBytes, "level" to level().name)

    private fun monthOf(at: Long) = MONTH.format(Instant.ofEpochMilli(at).atZone(zone))

    companion object {
        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")
        fun levelFor(used: Long, cap: Long): Level = when {
            cap <= 0 -> Level.OK
            used >= cap -> Level.OVER
            used * 10 >= cap * 8 -> Level.WARNING
            else -> Level.OK
        }
        private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
    }
}
