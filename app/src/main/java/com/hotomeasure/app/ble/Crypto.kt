package com.hotomeasure.app.ble

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto primitives used by the Xiaomi "standard auth" BLE handshake:
 * P-256 ECDH, HKDF-SHA256, HMAC-SHA256 and AES-CCM (12-byte nonce, 4-byte tag).
 * Android's providers don't offer AES-CCM, so it is built on AES-ECB here (RFC 3610).
 */
object Crypto {

    class EcKeyPair(val privateKey: PrivateKey, val publicRaw: ByteArray, val params: ECParameterSpec)

    fun generateKeyPair(): EcKeyPair {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub = kp.public as ECPublicKey
        return EcKeyPair(kp.private, fixed(pub.w.affineX) + fixed(pub.w.affineY), pub.params)
    }

    /** [remoteRaw] is X || Y, 32 bytes each, big-endian. */
    fun ecdh(own: EcKeyPair, remoteRaw: ByteArray): ByteArray {
        require(remoteRaw.size == 64) { "public key must be 64 bytes" }
        val point = ECPoint(BigInteger(1, remoteRaw.copyOfRange(0, 32)), BigInteger(1, remoteRaw.copyOfRange(32, 64)))
        val remote = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, own.params))
        return KeyAgreement.getInstance("ECDH").run {
            init(own.privateKey)
            doPhase(remote, true)
            generateSecret()
        }
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    /** RFC 5869. An empty or null salt means 32 zero bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt == null || salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            i++
        }
        return out
    }

    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0), tagLen: Int = 4): ByteArray {
        require(nonce.size == NONCE) { "nonce must be $NONCE bytes" }
        val aes = aes(key)
        val tag = cbcMac(aes, nonce, plain, aad, tagLen)
        val s0 = aes.doFinal(ctrBlock(nonce, 0))
        return ctr(aes, nonce, plain) + ByteArray(tagLen) { (tag[it].toInt() xor s0[it].toInt()).toByte() }
    }

    /** Returns null when the tag doesn't match. */
    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0), tagLen: Int = 4): ByteArray? {
        require(nonce.size == NONCE) { "nonce must be $NONCE bytes" }
        if (sealed.size < tagLen) return null
        val aes = aes(key)
        val ct = sealed.copyOfRange(0, sealed.size - tagLen)
        val plain = ctr(aes, nonce, ct)
        val s0 = aes.doFinal(ctrBlock(nonce, 0))
        val got = ByteArray(tagLen) { (sealed[ct.size + it].toInt() xor s0[it].toInt()).toByte() }
        return if (MessageDigest.isEqual(got, cbcMac(aes, nonce, plain, aad, tagLen))) plain else null
    }

    private const val NONCE = 12
    private const val L = 15 - NONCE // bytes for the length / counter field

    private fun aes(key: ByteArray) =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun ctrBlock(nonce: ByteArray, i: Int) = ByteArray(16).also {
        it[0] = (L - 1).toByte()
        nonce.copyInto(it, 1)
        for (k in 0 until L) it[15 - k] = (i shr (8 * k)).toByte()
    }

    private fun ctr(aes: Cipher, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = 1
        var pos = 0
        while (pos < data.size) {
            val ks = aes.doFinal(ctrBlock(nonce, block++))
            val n = minOf(16, data.size - pos)
            for (k in 0 until n) out[pos + k] = (data[pos + k].toInt() xor ks[k].toInt()).toByte()
            pos += n
        }
        return out
    }

    private fun cbcMac(aes: Cipher, nonce: ByteArray, msg: ByteArray, aad: ByteArray, tagLen: Int): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((tagLen - 2) / 2) shl 3) or (L - 1)).toByte()
        nonce.copyInto(b0, 1)
        for (k in 0 until L) b0[15 - k] = (msg.size shr (8 * k)).toByte()

        var data = b0
        if (aad.isNotEmpty()) {
            require(aad.size < 0xFF00) { "aad too long" }
            data += pad(byteArrayOf((aad.size shr 8).toByte(), aad.size.toByte()) + aad)
        }
        data += pad(msg)

        var x = ByteArray(16)
        for (off in data.indices step 16) {
            x = aes.doFinal(ByteArray(16) { (x[it].toInt() xor data[off + it].toInt()).toByte() })
        }
        return x.copyOf(tagLen)
    }

    private fun pad(b: ByteArray) = b.copyOf((b.size + 15) / 16 * 16)

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

fun String.unhex(): ByteArray {
    val s = filterNot { it.isWhitespace() }
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
