package com.hotomeasure.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundMeter(val address: String, val name: String, val rssi: Int, val device: BluetoothDevice)

/** Finds QWCJY001 units: name "xiaojiang.cc_9e2" or MiBeacon (FE95) product id 0x09E2. */
@SuppressLint("MissingPermission")
class Scanner(private val context: Context) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun scan(): Flow<FoundMeter> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off or unavailable"))
            return@callbackFlow
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toMeter()?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> r.toMeter()?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Bluetooth scan failed ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    private fun ScanResult.toMeter(): FoundMeter? {
        val record = scanRecord ?: return null
        val name = record.deviceName ?: runCatching { device.name }.getOrNull() ?: ""
        val mi = record.getServiceData(ParcelUuid(Uuids.XIAOMI_SERVICE))
        val pid = if (mi != null && mi.size >= 4) (mi[2].toInt() and 0xFF) or ((mi[3].toInt() and 0xFF) shl 8) else -1
        if (!name.startsWith("xiaojiang", ignoreCase = true) && pid != PRODUCT_ID) return null
        return FoundMeter(device.address, name.ifEmpty { "HOTO Laser Measure" }, rssi, device)
    }

    companion object {
        const val PRODUCT_ID = 0x09E2
    }
}
