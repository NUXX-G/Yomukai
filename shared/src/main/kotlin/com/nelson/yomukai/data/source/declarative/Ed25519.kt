package com.nelson.yomukai.data.source.declarative

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032, section 5.1.7), in plain Kotlin so it works the same on the server and on
 * every Android version (Android 8 has no Ed25519 in java.security). Only verification: signing happens elsewhere,
 * with a private key that never enters the repository. Slow-ish (a few ms) and used once per configuration download.
 */
object Ed25519 {
    // BigInteger.TWO only exists from Android 13 (API 33).
    private val TWO: BigInteger = BigInteger.valueOf(2)
    private val P: BigInteger = TWO.pow(255) - BigInteger.valueOf(19)
    private val Q: BigInteger = TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    /** A point in extended coordinates (X, Y, Z, T) with x = X/Z, y = Y/Z, x*y = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G: Point = run {
        val gy = BigInteger.valueOf(4).multiply(inv(BigInteger.valueOf(5))).mod(P)
        val gx = recoverX(gy, 0)!!
        Point(gx, gy, BigInteger.ONE, gx.multiply(gy).mod(P))
    }

    private fun inv(x: BigInteger): BigInteger = x.modPow(P - TWO, P)

    private fun add(a: Point, b: Point): Point {
        val two = TWO
        val ea = (a.y - a.x).multiply(b.y - b.x).mod(P)
        val eb = (a.y + a.x).multiply(b.y + b.x).mod(P)
        val ec = two.multiply(a.t).multiply(b.t).multiply(D).mod(P)
        val ed = two.multiply(a.z).multiply(b.z).mod(P)
        val e = eb - ea
        val f = ed - ec
        val g = ed + ec
        val h = eb + ea
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(scalar: BigInteger, point: Point): Point {
        var s = scalar
        var p = point
        var q = IDENTITY
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun same(a: Point, b: Point): Boolean =
        (a.x.multiply(b.z) - b.x.multiply(a.z)).mod(P).signum() == 0 && (a.y.multiply(b.z) - b.y.multiply(a.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = (y.multiply(y) - BigInteger.ONE).multiply(inv(D.multiply(y).multiply(y) + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if ((x.multiply(x) - x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if ((x.multiply(x) - x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P - x
        return x
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun decompress(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val raw = littleEndian(bytes)
        val sign = if (raw.testBit(255)) 1 else 0
        val y = raw.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** True when [signature] (64 bytes) is [publicKey]'s (32 bytes) signature of [message]. Never throws. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val digest = MessageDigest.getInstance("SHA-512").apply { update(rBytes); update(publicKey); update(message) }.digest()
        val h = littleEndian(digest).mod(Q)
        same(mul(s, G), add(r, mul(h, a)))
    }.getOrDefault(false)
}
