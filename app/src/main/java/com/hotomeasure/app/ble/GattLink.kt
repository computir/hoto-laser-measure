package com.hotomeasure.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

object Uuids {
    private fun short(v: Int) = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))
    val XIAOMI_SERVICE = short(0xFE95)
    val UPNP = short(0x0010)
    val AVDTP = short(0x0019)
    val DATA_SERVICE = short(0xFEB7)
    val DATA_WRITE = short(0xFEA7)
    val DATA_NOTIFY = short(0xFEA8)
    val CCCD = short(0x2902)
}

/**
 * One GATT connection to the measure. Notifications land in channels; every write waits for its
 * callback, because Android refuses a new GATT operation while one is still pending.
 * Callers must hold BLUETOOTH_CONNECT (checked by [MeterConnection]).
 */
@SuppressLint("MissingPermission")
class GattLink(private val context: Context) : AuthLink {

    override val upnp = Channel<ByteArray>(Channel.UNLIMITED)
    override val avdtp = Channel<ByteArray>(Channel.UNLIMITED)
    val session = Channel<ByteArray>(Channel.UNLIMITED)

    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Unit>()
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    private var gatt: BluetoothGatt? = null
    private var upnpChar: BluetoothGattCharacteristic? = null
    private var avdtpChar: BluetoothGattCharacteristic? = null
    private var dataWrite: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // status 8 = out of range / timed out, 19 = the measure hung up, 22 = this phone hung up
            Log.i("HotoMeasure", "GATT state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val cause = IOException("Disconnected (status $status)")
                connected.completeExceptionally(cause)
                discovered.completeExceptionally(cause)
                pending?.complete(-1)
                closeChannels(cause)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) discovered.complete(Unit)
            else discovered.completeExceptionally(IOException("Service discovery failed ($status)"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                Uuids.UPNP -> upnp.trySend(value)
                Uuids.AVDTP -> avdtp.trySend(value)
                Uuids.DATA_NOTIFY -> session.trySend(value)
            }
        }
    }

    suspend fun open(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not start a connection")
        try {
            withTimeout(15_000) { connected.await() }
            // No connection-priority request: renegotiating connection parameters can make
            // small BLE devices drop the link, and readings are tiny anyway.
            if (gatt?.discoverServices() != true) throw IOException("Could not discover services")
            withTimeout(10_000) { discovered.await() }

            val xiaomi = gatt?.getService(Uuids.XIAOMI_SERVICE) ?: throw IOException("This isn't a HOTO laser measure")
            val data = gatt?.getService(Uuids.DATA_SERVICE) ?: throw IOException("Measurement service missing")
            upnpChar = xiaomi.getCharacteristic(Uuids.UPNP) ?: throw IOException("Auth characteristic missing")
            avdtpChar = xiaomi.getCharacteristic(Uuids.AVDTP) ?: throw IOException("Auth data characteristic missing")
            dataWrite = data.getCharacteristic(Uuids.DATA_WRITE) ?: throw IOException("Command characteristic missing")
            val notify = data.getCharacteristic(Uuids.DATA_NOTIFY) ?: throw IOException("Measurement characteristic missing")
            enableNotify(upnpChar!!)
            enableNotify(avdtpChar!!)
            enableNotify(notify)
        } catch (e: TimeoutCancellationException) {
            close()
            throw IOException("Connection timed out")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override suspend fun writeUpnp(data: ByteArray) = write(upnpChar, data)
    override suspend fun writeAvdtp(data: ByteArray) = write(avdtpChar, data)
    suspend fun writeSession(frame: ByteArray) = write(dataWrite, frame)

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        closeChannels(IOException("Closed"))
    }

    private fun closeChannels(cause: Throwable) {
        upnp.close(cause)
        avdtp.close(cause)
        session.close()
    }

    private suspend fun enableNotify(c: BluetoothGattCharacteristic) {
        val g = gatt ?: throw IOException("Not connected")
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(Uuids.CCCD) ?: throw IOException("Notifications unsupported")
        val status = op { g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Could not enable notifications ($status)")
    }

    private suspend fun write(c: BluetoothGattCharacteristic?, data: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        c ?: throw IOException("Not connected")
        // UPNP supports write-with-response; the others are write-without-response.
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val status = op { g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Write failed ($status)")
    }

    private suspend fun op(start: () -> Boolean): Int = opLock.withLock {
        val done = CompletableDeferred<Int>()
        pending = done
        try {
            if (!start()) throw IOException("Bluetooth refused the operation")
            try {
                withTimeout(2_000) { done.await() }
            } catch (e: TimeoutCancellationException) {
                throw IOException("Bluetooth operation timed out")
            }
        } finally {
            pending = null
        }
    }
}
