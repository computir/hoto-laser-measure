package com.hotomeasure.app.ble

/**
 * Decrypted session messages.
 * Byte 0: bit 4 = direction (1 = from app), bits 0-3 = message id.
 * Byte 1: bits 4-7 = frame count - 1, bits 0-3 = frame index.
 * Byte 2: payload length. Byte 3: method (0x80 report, 0x01 set, 0xFF answer).
 * Report payload: a list of [type][id][big-endian value].
 */
data class Attribute(val type: Int, val id: Int, val value: Long)

sealed interface DeviceMessage {
    /** Every device report must be acknowledged, or the device repeats it about every 50 s. */
    val ack: ByteArray?

    data class Report(val attributes: List<Attribute>, override val ack: ByteArray) : DeviceMessage {
        fun value(type: Int, id: Int) = attributes.firstOrNull { it.type == type && it.id == id }?.value
    }

    data class Other(val raw: ByteArray, override val ack: ByteArray?) : DeviceMessage

    companion object {
        private const val FROM_APP = 0x10
        private const val METHOD_REPORT = 0x80

        fun parse(p: ByteArray): DeviceMessage {
            if (p.size < 4) return Other(p, null)
            val b0 = p[0].toInt() and 0xFF
            val b1 = p[1].toInt() and 0xFF
            if (b0 and FROM_APP != 0 || (p[3].toInt() and 0xFF) != METHOD_REPORT) return Other(p, null)
            val ack = byteArrayOf((b0 or FROM_APP).toByte(), b1.toByte())
            // Multi-frame reports haven't been seen from this device; acknowledge and skip them.
            if (b1 shr 4 != 0) return Other(p, ack)
            return Report(attributes(p), ack)
        }

        private fun attributes(p: ByteArray): List<Attribute> {
            val out = mutableListOf<Attribute>()
            var i = 4
            while (i + 2 <= p.size) {
                val type = p[i].toInt() and 0xFF
                val width = when (type) {
                    0, 1, 2 -> 1
                    3, 4 -> 2
                    5, 6, 9 -> 4
                    7, 8, 0x0A -> 8
                    else -> break
                }
                if (i + 2 + width > p.size) break
                var v = 0L
                for (k in 0 until width) v = (v shl 8) or (p[i + 2 + k].toLong() and 0xFF)
                out += Attribute(type, p[i + 1].toInt() and 0xFF, v)
                i += 2 + width
            }
            return out
        }
    }
}

object Attr {
    const val DISTANCE_TYPE = 0x04 // u16, millimetres
    const val DISTANCE_ID = 0x00
    const val SETTING_TYPE = 0x02
    const val REFERENCE_ID = 0x01 // 0 = back edge, 1 = front edge
    const val UNIT_ID = 0x02 // 0 = metres, 1 = feet

    /** A "set value" command; [msgId] is 1..15. */
    fun set(msgId: Int, id: Int, value: Int) =
        byteArrayOf(msgId.toByte(), 0, 4, 0x01, SETTING_TYPE.toByte(), id.toByte(), value.toByte())
}
