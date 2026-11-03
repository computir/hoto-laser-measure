package com.hotomeasure.app

import com.hotomeasure.app.ble.Attr
import com.hotomeasure.app.ble.Attribute
import com.hotomeasure.app.ble.AuthLink
import com.hotomeasure.app.ble.Crypto
import com.hotomeasure.app.ble.DeviceMessage
import com.hotomeasure.app.ble.MiAuth
import com.hotomeasure.app.ble.SessionKeys
import com.hotomeasure.app.ble.hex
import com.hotomeasure.app.ble.unhex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.random.Random

class ProtocolTest {

    // Session keys and frames captured from a real QWCJY001 (published alongside the protocol notes).
    private val session = SessionKeys(
        devKey = "930ea6c77879cd9f1b28c3b62cf3ca65".unhex(),
        appKey = "857e1e6258148b6a3b87710182e03a99".unhex(),
        devIv = "df3b10e5".unhex(),
        appIv = "2f9c872a".unhex(),
    )

    private fun bcCcm(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = CCMBlockCipher.newInstance(AESEngine.newInstance())
        c.init(true, AEADParameters(KeyParameter(key), 32, nonce, aad))
        val out = ByteArray(c.getOutputSize(plain.size))
        val n = c.processBytes(plain, 0, plain.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    @Test
    fun ccmMatchesBouncyCastle() {
        val r = Random(42)
        repeat(200) {
            val key = r.nextBytes(16)
            val nonce = r.nextBytes(12)
            val plain = r.nextBytes(r.nextInt(0, 70))
            val aad = r.nextBytes(r.nextInt(0, 30))
            val ours = Crypto.ccmEncrypt(key, nonce, plain, aad)
            assertEquals(bcCcm(key, nonce, plain, aad).hex(), ours.hex())
            assertArrayEquals(plain, Crypto.ccmDecrypt(key, nonce, ours, aad))
        }
    }

    @Test
    fun ccmRejectsTampering() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12)
        val ct = Crypto.ccmEncrypt(key, nonce, "hello".toByteArray())
        ct[1] = (ct[1].toInt() xor 1).toByte()
        assertNull(Crypto.ccmDecrypt(key, nonce, ct))
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val okm = Crypto.hkdf(
            "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".unhex(),
            "000102030405060708090a0b0c".unhex(),
            "f0f1f2f3f4f5f6f7f8f9".unhex(), 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun decryptsRealMeasurements() {
        val m1 = DeviceMessage.parse(session.open("0300e1a01fdf6c1986fd7023fb31".unhex())!!) as DeviceMessage.Report
        val m2 = DeviceMessage.parse(session.open("040058d3299a37dda3eaa90a8b07".unhex())!!) as DeviceMessage.Report
        assertEquals(listOf(Attribute(4, 0, 1463)), m1.attributes)
        assertEquals(listOf(Attribute(4, 0, 434)), m2.attributes)
    }

    @Test
    fun decryptsRealSettingsReports() {
        val ref = DeviceMessage.parse(session.open("01006d0926c408cb2248da3cd2".unhex())!!) as DeviceMessage.Report
        val unit = DeviceMessage.parse(session.open("0200426e003d795463e97bcc39".unhex())!!) as DeviceMessage.Report
        assertEquals(0L, ref.value(Attr.SETTING_TYPE, Attr.REFERENCE_ID))
        assertEquals(0L, unit.value(Attr.SETTING_TYPE, Attr.UNIT_ID))
        assertEquals("1000", ref.ack.hex())
    }

    @Test
    fun parsesMultiAttributeReport() {
        val r = DeviceMessage.parse("0300068002020104000a5b".unhex()) as DeviceMessage.Report
        assertEquals(listOf(Attribute(2, 2, 1), Attribute(4, 0, 2651)), r.attributes)
        assertEquals("1300", r.ack.hex())
        assertTrue(DeviceMessage.parse("030001ff00".unhex()) is DeviceMessage.Other)
    }

    @Test
    fun sealedCommandsDecryptWithAppKey() {
        val cmd = Attr.set(3, Attr.UNIT_ID, 1)
        assertEquals("03000401020201", cmd.hex())
        val sealer = session.Sealer()
        repeat(4) { sealer.seal(ByteArray(0)) }
        val frame = sealer.seal(cmd)
        assertEquals("0500", frame.copyOfRange(0, 2).hex())
        val nonce = SessionKeys.nonce(session.appIv, 5)
        assertArrayEquals(cmd, Crypto.ccmDecrypt(session.appKey, nonce, frame.copyOfRange(2, frame.size)))
    }

    /** Replays a recorded login exchange and checks we derive the recorded session keys. */
    @Test
    fun loginMatchesRecordedSession() = runBlocking {
        val token = "caba10d7494e3f63482c5266".unhex()
        val appRandom = "70fdf4d78fed636cafa838ea1c3feda8".unhex()
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { appRandom.copyInto(bytes) }
        }
        val link = ScriptedLink(
            listOf(
                "0000000b0100" to listOf("00000101"),
                "010070fdf4d78fed636cafa838ea1c3feda8" to listOf("00000100", "0000000d0100"),
                "00000101" to listOf("010092acfd7519f2f070c32ac276bc5a7872"),
                "00000100" to listOf("0000000c0200"),
                "00000101" to listOf("0100a8c49cb833cea6cdc9df40f9390d3f578aa3", "0200f0cb3504cf325df53cfc371eb237"),
                "00000100" to emptyList(),
                "0000000a0200" to listOf("00000101"),
                "01003041cd0247af8aa5fe82dd17bcaf43a690bb" to emptyList(),
                "02009ed1e390b5252b5e7c4c89a553b9" to listOf("00000100"),
            ),
            upnpAtEnd = "21000000",
        )
        val keys = MiAuth(link, fixed).login(token)
        assertArrayEquals(session.devKey, keys.devKey)
        assertArrayEquals(session.appKey, keys.appKey)
        assertArrayEquals(session.devIv, keys.devIv)
        assertArrayEquals(session.appIv, keys.appIv)
        assertEquals(listOf("24000000"), link.upnpWrites)
    }

    @Test
    fun registerAgainstSimulatedDevice() = runBlocking {
        val dev = SimulatedDevice()
        val token = MiAuth(dev).register()
        assertArrayEquals(dev.token, token)
        assertEquals(dev.did.hex(), dev.receivedDid?.hex())
    }

    private class ScriptedLink(private val script: List<Pair<String, List<String>>>, private val upnpAtEnd: String) : AuthLink {
        override val upnp = Channel<ByteArray>(Channel.UNLIMITED)
        override val avdtp = Channel<ByteArray>(Channel.UNLIMITED)
        val upnpWrites = mutableListOf<String>()
        private var i = 0

        override suspend fun writeUpnp(data: ByteArray) { upnpWrites += data.hex() }

        override suspend fun writeAvdtp(data: ByteArray) {
            val (expect, replies) = script[i]
            assertEquals("AVDTP write #$i", expect, data.hex())
            i++
            replies.forEach { avdtp.trySend(it.unhex()) }
            if (i == script.size) upnp.trySend(upnpAtEnd.unhex())
        }
    }

    /** Plays the device side of registration. */
    private class SimulatedDevice : AuthLink {
        override val upnp = Channel<ByteArray>(Channel.UNLIMITED)
        override val avdtp = Channel<ByteArray>(Channel.UNLIMITED)
        private val own = Crypto.generateKeyPair()
        val did = "626c742e332e3132333435363738396162636465".unhex()
        private val info = "01000000".unhex() + did
        var token: ByteArray? = null
        var receivedDid: ByteArray? = null
        private var appPub: ByteArray? = null
        private var pendingOut: List<ByteArray> = emptyList()
        private var expecting = ""
        private var left = 0
        private var buf = ByteArrayOutputStream()

        private fun sendParcel(header: String, payload: ByteArray) {
            pendingOut = (payload.indices step 18).mapIndexed { n, off ->
                byteArrayOf((n + 1).toByte(), 0) + payload.copyOfRange(off, minOf(off + 18, payload.size))
            }
            avdtp.trySend(header.unhex())
        }

        override suspend fun writeUpnp(data: ByteArray) {
            when (data.hex()) {
                "a2000000" -> sendParcel("000000000200", info)
                "15000000" -> expecting = "key"
                "13000000" -> {
                    val d = Crypto.hkdf(Crypto.ecdh(own, appPub!!), null, "mible-setup-info".toByteArray(), 64)
                    token = d.copyOfRange(0, 12)
                    upnp.trySend((if (receivedDid != null) "11000000" else "12000000").unhex())
                }
            }
        }

        override suspend fun writeAvdtp(data: ByteArray) {
            val h = data.hex()
            when {
                h == "00000101" -> { pendingOut.forEach { avdtp.trySend(it) }; pendingOut = emptyList() }
                h == "00000100" -> Unit
                left == 0 && data.size == 6 -> {
                    if (h == "000000000200") expecting = "did"
                    left = data[4].toInt(); buf = ByteArrayOutputStream()
                    avdtp.trySend("00000101".unhex())
                }
                left > 0 -> {
                    buf.write(data, 2, data.size - 2)
                    if (--left == 0) {
                        avdtp.trySend("00000100".unhex())
                        val got = buf.toByteArray()
                        if (expecting == "key") {
                            appPub = got
                            sendParcel("000000030400", own.publicRaw)
                        } else {
                            val d = Crypto.hkdf(Crypto.ecdh(own, appPub!!), null, "mible-setup-info".toByteArray(), 64)
                            receivedDid = Crypto.ccmDecrypt(d.copyOfRange(28, 44), ByteArray(12) { (0x10 + it).toByte() }, got, "devID".toByteArray())
                        }
                    }
                }
            }
        }
    }
}
