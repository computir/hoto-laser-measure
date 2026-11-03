package com.hotomeasure.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.hotomeasure.app.autostart.AutoStart
import com.hotomeasure.app.data.PairedMeter
import com.hotomeasure.app.data.Store
import com.hotomeasure.app.data.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class LinkState { NOT_PAIRED, NEEDS_PERMISSION, BLUETOOTH_OFF, SEARCHING, CONNECTING, CONNECTED, NEEDS_REPAIR }

enum class Edge { BACK, FRONT }
enum class MeterUnit { METRES, FEET }

data class MeterStatus(val edge: Edge? = null, val unit: MeterUnit? = null)

/** A reading as the device sent it, in millimetres. */
data class Reading(val mm: Int, val time: Long)

/**
 * Keeps the paired measure connected while the app is in the foreground, logs in on every
 * connection, and turns device reports into [readings].
 */
@SuppressLint("MissingPermission")
class MeterConnection(
    private val context: Context,
    private val store: Store,
    val scanner: Scanner,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(LinkState.NOT_PAIRED)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _readings = MutableSharedFlow<Reading>(extraBufferCapacity = 16)
    val readings: SharedFlow<Reading> = _readings.asSharedFlow()

    private val _status = MutableStateFlow(MeterStatus())
    val status: StateFlow<MeterStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val pairLock = Mutex()
    @Volatile private var session: Session? = null
    private var loop: Job? = null
    private var foreground = false

    fun hasPermissions() = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private var pendingStop: Job? = null

    /**
     * Connects while [value] is true. When it turns false, the link is kept for [graceMs] first,
     * so locking the phone or switching apps for a moment doesn't drop the measure.
     */
    fun setForeground(value: Boolean, graceMs: Long = 0) {
        foreground = value
        pendingStop?.cancel()
        pendingStop = null
        when {
            value -> start()
            graceMs > 0 -> pendingStop = scope.launch {
                delay(graceMs)
                if (!foreground) stop()
            }
            else -> stop()
        }
    }

    /** Registers with the measure. This replaces any pairing it had with another phone or app. */
    suspend fun pair(found: FoundMeter): Result<Unit> = pairLock.withLock {
        if (!hasPermissions()) return Result.failure(IllegalStateException("Bluetooth permission not granted"))
        stop()
        try {
            val link = GattLink(context)
            try {
                link.open(found.device)
                val token = MiAuth(link).register()
                store.savePaired(PairedMeter(found.address, found.name, token.hex()))
            } finally {
                link.close()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pairing failed", e)
            Result.failure(e)
        } finally {
            if (foreground) start()
        }
    }

    suspend fun forget() {
        stop()
        store.forget()
        _state.value = LinkState.NOT_PAIRED
        if (foreground) start()
    }

    suspend fun setEdge(edge: Edge) = send(Attr.REFERENCE_ID, edge.ordinal)
    suspend fun setMeterUnit(unit: MeterUnit) = send(Attr.UNIT_ID, unit.ordinal)

    private suspend fun send(id: Int, value: Int): Boolean {
        val s = session ?: return false
        return runCatching { s.set(id, value) }.isSuccess
    }

    private fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            store.paired.collectLatest { p -> if (p == null) _state.value = LinkState.NOT_PAIRED else connectLoop(p) }
        }
    }

    private fun stop() {
        // The measure will start advertising again now; don't let that auto-open the app.
        if (loop?.isActive == true) AutoStart.markReleased(context)
        loop?.cancel()
        loop = null
    }

    private suspend fun connectLoop(paired: PairedMeter) {
        val token = runCatching { paired.tokenHex.unhex() }.getOrNull()?.takeIf { it.size == 12 }
        if (token == null) {
            _state.value = LinkState.NEEDS_REPAIR
            return
        }
        var failures = 0
        while (true) {
            if (!hasPermissions()) { _state.value = LinkState.NEEDS_PERMISSION; delay(1000); continue }
            if (!scanner.isBluetoothOn) { _state.value = LinkState.BLUETOOTH_OFF; delay(1000); continue }
            _state.value = LinkState.SEARCHING
            val link = GattLink(context)
            try {
                // The measure only advertises while nothing is connected to it.
                val found = withTimeoutOrNull(20_000) { scanner.scan().first { it.address == paired.address } } ?: continue
                _state.value = LinkState.CONNECTING
                link.open(found.device)
                val keys = MiAuth(link).login(token)
                val s = Session(link, keys).also { session = it }
                _state.value = LinkState.CONNECTED
                _lastError.value = null
                failures = 0
                val receiver = keys.Receiver()
                for (frame in link.session) {
                    val plain = receiver.open(frame) ?: continue
                    val msg = DeviceMessage.parse(plain)
                    // Show the reading first so it isn't lost if the acknowledgement has trouble.
                    if (msg is DeviceMessage.Report) onReport(msg)
                    msg.ack?.let { ack(s, it) }
                    if (msg is DeviceMessage.Report) keepMeterOnAppUnits(msg, s)
                }
                Log.i(TAG, "Measure closed the connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotRecognisedException) {
                Log.w(TAG, "Login refused", e)
                _state.value = LinkState.NEEDS_REPAIR
                return
            } catch (e: Exception) {
                Log.i(TAG, "Connection dropped", e)
                _lastError.value = e.message
                failures++
            } finally {
                session = null
                _status.value = MeterStatus()
                link.close()
            }
            // Reconnect quickly after a drop; back off only if it keeps failing.
            delay(minOf(300L * (failures + 1), 5_000L))
        }
    }

    /**
     * Retries a failed acknowledgement once. If it fails again, reconnect: a GATT write that never
     * completed can leave Android refusing every later write, and an unacknowledged report is
     * re-sent by the measure about 50 s later, which would show up as a duplicate reading.
     * The reading itself was already delivered before the acknowledgement, so nothing is lost.
     */
    private suspend fun ack(s: Session, ack: ByteArray) {
        repeat(2) { attempt ->
            try {
                s.write(ack)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Acknowledgement failed (attempt ${attempt + 1}): ${e.message}")
                delay(150)
            }
        }
        throw java.io.IOException("The measure isn't accepting replies; reconnecting")
    }

    /**
     * The measure has no unit button, so the app's choice wins: when the measure reports a
     * different display unit (e.g. left on metres by Mi Home), switch it to match the app.
     */
    private suspend fun keepMeterOnAppUnits(r: DeviceMessage.Report, s: Session) {
        val shown = r.value(Attr.SETTING_TYPE, Attr.UNIT_ID)?.toInt()?.let { MeterUnit.entries.getOrNull(it) } ?: return
        val wanted = if (Units.isImperial(store.settings.first().unit)) MeterUnit.FEET else MeterUnit.METRES
        if (shown != wanted) {
            Log.i(TAG, "Measure shows $shown, app uses $wanted; switching the measure")
            runCatching { s.set(Attr.UNIT_ID, wanted.ordinal) }
        }
    }

    private fun onReport(r: DeviceMessage.Report) {
        r.value(Attr.DISTANCE_TYPE, Attr.DISTANCE_ID)?.let { _readings.tryEmit(Reading(it.toInt(), System.currentTimeMillis())) }
        r.value(Attr.SETTING_TYPE, Attr.REFERENCE_ID)?.let { v ->
            Edge.entries.getOrNull(v.toInt())?.let { e -> _status.update { it.copy(edge = e) } }
        }
        r.value(Attr.SETTING_TYPE, Attr.UNIT_ID)?.let { v ->
            MeterUnit.entries.getOrNull(v.toInt())?.let { u -> _status.update { it.copy(unit = u) } }
        }
    }

    private class Session(private val link: GattLink, keys: SessionKeys) {
        private val lock = Mutex()
        private val sealer = keys.Sealer()
        private var msgId = 0

        suspend fun write(plain: ByteArray) = lock.withLock { link.writeSession(sealer.seal(plain)) }

        suspend fun set(id: Int, value: Int) = lock.withLock {
            msgId = msgId % 15 + 1
            link.writeSession(sealer.seal(Attr.set(msgId, id, value)))
        }
    }

    companion object {
        private const val TAG = "HotoMeasure"
        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
