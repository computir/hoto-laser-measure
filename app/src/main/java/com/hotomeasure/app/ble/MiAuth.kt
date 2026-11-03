package com.hotomeasure.app.ble

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** The two Xiaomi auth characteristics: UPNP carries commands, AVDTP carries data "parcels". */
interface AuthLink {
    suspend fun writeUpnp(data: ByteArray)
    suspend fun writeAvdtp(data: ByteArray)
    val upnp: ReceiveChannel<ByteArray>
    val avdtp: ReceiveChannel<ByteArray>
}

open class AuthException(message: String) : Exception(message)

/** The device no longer accepts our token, usually because another app paired with it since. */
class NotRecognisedException(message: String) : AuthException(message)

class SessionKeys(val devKey: ByteArray, val appKey: ByteArray, val devIv: ByteArray, val appIv: ByteArray) {

    /** A notification from FEA8 is counter (u16 LE) || ciphertext || 4-byte tag. */
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 7) return null
        val counter = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
        return Crypto.ccmDecrypt(devKey, nonce(devIv, counter), frame.copyOfRange(2, frame.size))
    }

    /** Seals app-to-device frames. One per connection: the device ignores counters it has seen. */
    inner class Sealer {
        private var counter = 0
        fun seal(plain: ByteArray): ByteArray {
            counter++
            return byteArrayOf(counter.toByte(), (counter shr 8).toByte()) +
                Crypto.ccmEncrypt(appKey, nonce(appIv, counter), plain)
        }
    }

    companion object {
        fun nonce(iv: ByteArray, counter: Int) = iv + ByteArray(4) +
            byteArrayOf(counter.toByte(), (counter shr 8).toByte(), (counter shr 16).toByte(), (counter shr 24).toByte())
    }
}

/**
 * Registration (pairing, once) produces a 12-byte token; login (every connection) turns it
 * into session keys. The steps follow the Xiaomi "standard auth" used by the QWCJY001.
 */
class MiAuth(
    private val link: AuthLink,
    private val random: SecureRandom = SecureRandom(),
    private val stepTimeoutMs: Long = 8_000,
) {
    suspend fun register(): ByteArray {
        val own = Crypto.generateKeyPair()

        link.writeUpnp(CMD_GET_INFO)
        val info = receiveParcel()
        if (info.size < 24) throw AuthException("Unexpected device info (${info.size} bytes)")
        val did = info.copyOfRange(4, 24)

        link.writeUpnp(CMD_SET_KEY)
        sendParcel(HDR_PUBLIC_KEY, own.publicRaw)
        val remoteKey = receiveParcel()
        if (remoteKey.size != 64) throw AuthException("Unexpected public key length ${remoteKey.size}")

        val derived = Crypto.hkdf(Crypto.ecdh(own, remoteKey), null, "mible-setup-info".toByteArray(), 64)
        val token = derived.copyOfRange(0, 12)
        val keyA = derived.copyOfRange(28, 44)
        val nonce = ByteArray(12) { (0x10 + it).toByte() }
        sendParcel(HDR_DID, Crypto.ccmEncrypt(keyA, nonce, did, "devID".toByteArray()))

        link.writeUpnp(CMD_AUTH)
        val result = receive(link.upnp)
        if (!result.contentEquals(REGISTER_OK)) throw AuthException("Pairing was refused (${result.hex()})")
        return token
    }

    suspend fun login(token: ByteArray): SessionKeys {
        require(token.size == 12)
        val appRandom = ByteArray(16).also(random::nextBytes)

        link.writeUpnp(CMD_LOGIN)
        sendParcel(HDR_RANDOM, appRandom)

        val first = receive(link.avdtp)
        val devRandom = if (first.size == 20 && first.copyOfRange(0, 4).contentEquals(INLINE_RANDOM)) {
            link.writeAvdtp(INLINE_RANDOM_ACK)
            first.copyOfRange(4, 20)
        } else {
            receiveParcel(first)
        }
        if (devRandom.size != 16) throw AuthException("Unexpected device random length ${devRandom.size}")
        val devProof = receiveParcel()

        val d = Crypto.hkdf(token, appRandom + devRandom, "mible-login-info".toByteArray(), 64)
        val keys = SessionKeys(d.copyOfRange(0, 16), d.copyOfRange(16, 32), d.copyOfRange(32, 36), d.copyOfRange(36, 40))
        if (!Crypto.hmac(keys.devKey, devRandom + appRandom).contentEquals(devProof)) {
            throw NotRecognisedException("The measure doesn't recognise this phone any more")
        }
        sendParcel(HDR_PROOF, Crypto.hmac(keys.appKey, appRandom + devRandom))

        val result = receive(link.upnp)
        if (!result.contentEquals(LOGIN_OK)) throw NotRecognisedException("Login refused (${result.hex()})")
        return keys
    }

    private suspend fun receiveParcel(first: ByteArray? = null): ByteArray {
        val header = first ?: receive(link.avdtp)
        if (header.size < 6 || header[0].toInt() != 0 || header[1].toInt() != 0) {
            throw AuthException("Expected a data header, got ${header.hex()}")
        }
        val count = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
        link.writeAvdtp(RCV_READY)
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < count) {
            val frame = receive(link.avdtp)
            if (frame.size < 2) throw AuthException("Short frame")
            index = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
            out.write(frame, 2, frame.size - 2)
        }
        link.writeAvdtp(RCV_OK)
        return out.toByteArray()
    }

    private suspend fun sendParcel(header: ByteArray, data: ByteArray) {
        link.writeAvdtp(header)
        val ready = receive(link.avdtp)
        if (!ready.contentEquals(RCV_READY)) throw AuthException("Measure not ready (${ready.hex()})")
        var n = 1
        for (off in data.indices step FRAME_DATA) {
            link.writeAvdtp(byteArrayOf(n.toByte(), (n shr 8).toByte()) + data.copyOfRange(off, minOf(off + FRAME_DATA, data.size)))
            n++
        }
        val ok = receive(link.avdtp)
        if (!ok.contentEquals(RCV_OK)) throw AuthException("Data not accepted (${ok.hex()})")
    }

    private suspend fun receive(ch: ReceiveChannel<ByteArray>): ByteArray = try {
        withTimeout(stepTimeoutMs) { ch.receive() }
    } catch (e: TimeoutCancellationException) {
        throw AuthException("The measure stopped responding")
    }

    companion object {
        private const val FRAME_DATA = 18
        private val CMD_GET_INFO = "a2000000".unhex()
        private val CMD_SET_KEY = "15000000".unhex()
        private val CMD_AUTH = "13000000".unhex()
        private val CMD_LOGIN = "24000000".unhex()
        // Parcel headers: 00 00, type (u16), frame count (u16 LE).
        private val HDR_PUBLIC_KEY = "000000030400".unhex()
        private val HDR_DID = "000000000200".unhex()
        private val HDR_RANDOM = "0000000b0100".unhex()
        private val HDR_PROOF = "0000000a0200".unhex()
        private val RCV_READY = "00000101".unhex()
        private val RCV_OK = "00000100".unhex()
        private val INLINE_RANDOM = "0000020d".unhex()
        private val INLINE_RANDOM_ACK = "00000300".unhex()
        private val REGISTER_OK = "11000000".unhex()
        private val LOGIN_OK = "21000000".unhex()
    }
}
