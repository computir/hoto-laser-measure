package com.hotomeasure.app.ui

import android.app.Application
import android.content.Context
import android.content.IntentSender
import com.hotomeasure.app.autostart.AutoStart
import com.hotomeasure.app.update.Release
import com.hotomeasure.app.update.UpdateState
import com.hotomeasure.app.update.Updater
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hotomeasure.app.MeasureApp
import com.hotomeasure.app.ble.Edge
import com.hotomeasure.app.ble.FoundMeter
import com.hotomeasure.app.ble.MeterUnit
import com.hotomeasure.app.data.Corner
import com.hotomeasure.app.data.Measurement
import com.hotomeasure.app.data.RoomDraft
import com.hotomeasure.app.data.RoomMath
import com.hotomeasure.app.data.RoomResult
import com.hotomeasure.app.data.Settings
import com.hotomeasure.app.data.Units
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class Mode(val label: String, val sides: List<String>) {
    DISTANCE("Distance", listOf("Distance")),
    AREA("Area", listOf("Length", "Width")),
    VOLUME("Volume", listOf("Length", "Width", "Height")),
    ROOM("Room", emptyList()),
}

/** Where the next reading goes while measuring a room. */
sealed interface RoomTarget {
    data object NextWall : RoomTarget
    data class Wall(val index: Int) : RoomTarget
    data object Height : RoomTarget
}

data class Workspace(
    val mode: Mode = Mode.DISTANCE,
    val sides: List<Int?> = listOf(null),
    /** The side the next reading goes into. */
    val target: Int = 0,
    val last: Int? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app as MeasureApp
    private val store = ctx.store
    val meter = ctx.meter

    val state = meter.state
    val status = meter.status
    val lastError = meter.lastError
    val paired = store.paired.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<Settings> = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val history = store.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _ws = MutableStateFlow(Workspace())
    val workspace = _ws.asStateFlow()

    private val _found = MutableStateFlow<List<FoundMeter>>(emptyList())
    val found = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    private val _pairing = MutableStateFlow<String?>(null) // address being paired
    val pairing = _pairing.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            meter.readings.collect { r -> onReading(r.mm) }
        }
    }

    private suspend fun onReading(mm: Int) {
        val ws = _ws.value
        if (ws.mode == Mode.ROOM) {
            _ws.value = ws.copy(last = mm)
            onRoomReading(mm)
            return
        }
        val sides = ws.sides.toMutableList().also { it[ws.target] = mm }
        val next = sides.indexOfFirst { it == null }.let { if (it < 0) (ws.target + 1) % sides.size else it }
        _ws.value = ws.copy(sides = sides, target = if (ws.mode == Mode.DISTANCE) 0 else next, last = mm)
        if (ws.mode == Mode.DISTANCE) save(Mode.DISTANCE, listOf(mm))
    }

    fun setMode(m: Mode) = _ws.update { Workspace(mode = m, sides = List(m.sides.size) { null }, last = it.last) }
    fun selectSide(i: Int) = _ws.update { it.copy(target = i) }
    fun clearSides() = _ws.update { it.copy(sides = List(it.mode.sides.size) { null }, target = 0) }

    /** Saves a completed area or volume to history. */
    fun saveResult() = viewModelScope.launch {
        val ws = _ws.value
        if (ws.sides.any { it == null }) return@launch
        save(ws.mode, ws.sides.map { it!! })
        _message.value = "${ws.mode.label} saved"
        clearSides()
    }

    private suspend fun save(mode: Mode, mm: List<Int>) {
        val now = System.currentTimeMillis()
        store.editHistory { listOf(Measurement(now, now, mode.name.lowercase(), mm)) + it }
    }

    // --- Room mode ---

    val roomDraft = store.roomDraft.stateIn(viewModelScope, SharingStarted.Eagerly, RoomDraft())
    private val _roomTarget = MutableStateFlow<RoomTarget>(RoomTarget.NextWall)
    val roomTarget = _roomTarget.asStateFlow()

    private fun editRoom(f: (RoomDraft) -> RoomDraft) = viewModelScope.launch { store.saveRoomDraft(f(roomDraft.value)) }

    private suspend fun onRoomReading(mm: Int) {
        val d = roomDraft.value
        val next = when (val t = _roomTarget.value) {
            RoomTarget.NextWall -> d.copy(walls = d.walls + mm)
            is RoomTarget.Wall -> if (t.index in d.walls.indices) d.copy(walls = d.walls.toMutableList().also { it[t.index] = mm }) else d.copy(walls = d.walls + mm)
            RoomTarget.Height -> d.copy(heightMm = mm)
        }
        store.saveRoomDraft(next)
        _roomTarget.value = RoomTarget.NextWall
    }

    fun selectRoomTarget(t: RoomTarget) {
        _roomTarget.value = if (_roomTarget.value == t) RoomTarget.NextWall else t
    }

    /** Flips the corner at the end of wall [i] between inside and outside. */
    fun toggleCorner(i: Int) = editRoom { d ->
        val cs = Corner.decode(d.corners, d.walls.size).toMutableList()
        cs[i] = cs[i].toggled()
        d.copy(corners = Corner.encode(cs))
    }

    fun undoWall() = editRoom { d ->
        if (d.walls.isEmpty()) d else d.copy(walls = d.walls.dropLast(1), corners = d.corners.take(d.walls.size - 1))
    }

    fun clearRoom() {
        _roomTarget.value = RoomTarget.NextWall
        editRoom { RoomDraft() }
    }

    fun clearRoomHeight() = editRoom { it.copy(heightMm = null) }

    /** Saves a room that closes to history under [name]. */
    fun saveRoom(name: String) = viewModelScope.launch {
        val d = roomDraft.value
        val r = RoomMath.solve(d.walls, Corner.decode(d.corners, d.walls.size)) as? RoomResult.Closed ?: return@launch
        val now = System.currentTimeMillis()
        val m = Measurement(
            id = now, time = now, kind = "room", mm = r.walls, label = name.trim(),
            corners = Corner.encode(r.corners), heightMm = d.heightMm,
        )
        store.editHistory { listOf(m) + it }
        clearRoom()
        _message.value = "Room saved to History"
    }

    fun rename(id: Long, label: String) = viewModelScope.launch {
        store.editHistory { list -> list.map { if (it.id == id) it.copy(label = label.trim()) else it } }
    }

    fun delete(id: Long) = viewModelScope.launch { store.editHistory { list -> list.filterNot { it.id == id } } }
    fun clearHistory() = viewModelScope.launch { store.editHistory { emptyList() } }

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch {
        store.updateSettings(f)
        // Keep the measure's own screen in the same system as the app.
        val imperial = Units.isImperial(store.settings.first().unit)
        meter.setMeterUnit(if (imperial) MeterUnit.FEET else MeterUnit.METRES)
    }

    fun setEdge(e: Edge) = viewModelScope.launch {
        if (!meter.setEdge(e)) _message.value = "Connect the measure first"
    }

    fun startScan() {
        scanJob?.cancel()
        _found.value = emptyList()
        _scanning.value = true
        meter.setForeground(false) // the measure must be disconnected to advertise
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(15_000) {
                    meter.scanner.scan()
                        .catch { _message.value = it.message }
                        .collect { f ->
                            _found.update { list ->
                                (list.filterNot { it.address == f.address } + f).sortedByDescending { it.rssi }
                            }
                        }
                }
            } finally {
                _scanning.value = false
                if (_pairing.value == null) meter.setForeground(true)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    fun pair(f: FoundMeter) = viewModelScope.launch {
        _pairing.value = f.address
        scanJob?.cancel()
        _scanning.value = false
        val r = meter.pair(f)
        _pairing.value = null
        meter.setForeground(true)
        _message.value = r.fold({ "Paired with ${f.name}" }, { "Pairing failed: ${it.message}" })
        if (r.isSuccess) _found.value = emptyList()
    }

    fun forget() = viewModelScope.launch {
        paired.value?.let { AutoStart.disable(ctx, it.address) }
        meter.forget()
        refreshAutoStart()
        _message.value = "Measure forgotten"
    }

    // --- Open automatically when the measure is switched on ---

    private val _autoStart = MutableStateFlow(false)
    val autoStart = _autoStart.asStateFlow()
    private val _autoStartBusy = MutableStateFlow(false)
    val autoStartBusy = _autoStartBusy.asStateFlow()

    fun refreshAutoStart() {
        val address = paired.value?.address
        _autoStart.value = address != null && AutoStart.isEnabled(ctx, address)
        // Re-arm watching on every launch; harmless if it's already active.
        if (_autoStart.value) AutoStart.observe(ctx, address!!)
    }

    /** Android shows its own "allow Laser Measure to access…" dialog via [onPending]. */
    fun enableAutoStart(onPending: (IntentSender) -> Unit) {
        val address = paired.value?.address ?: return
        _autoStartBusy.value = true
        // The measure only advertises (and so can only be found) while it isn't connected.
        meter.setForeground(false)
        AutoStart.request(ctx, address, onPending) { error ->
            _autoStartBusy.value = false
            meter.setForeground(true)
            _message.value = "Couldn't set up auto-open: $error. Make sure the measure is on."
        }
    }

    fun autoStartDialogClosed(accepted: Boolean) {
        _autoStartBusy.value = false
        paired.value?.let { if (accepted) AutoStart.observe(ctx, it.address) }
        meter.setForeground(true)
        refreshAutoStart()
        if (accepted) _message.value = if (_autoStart.value) "The app will open when the measure is switched on" else "Auto-open wasn't set up"
    }

    fun disableAutoStart() {
        paired.value?.let { AutoStart.disable(ctx, it.address) }
        refreshAutoStart()
    }

    // Declared after the auto-start state above, which it reads.
    init {
        viewModelScope.launch { paired.collect { refreshAutoStart() } }
    }

    // --- Updates from GitHub releases ---

    val update = Updater.state

    fun checkForUpdates() = viewModelScope.launch { Updater.check(ctx) }

    /** False when Android first needs "Install unknown apps" allowed for this app. */
    fun canInstall() = ctx.packageManager.canRequestPackageInstalls()

    fun installUpdate(release: Release) = viewModelScope.launch { Updater.downloadAndInstall(ctx, release) }

    init {
        // Quietly look for a new release about once a day, if the person hasn't turned that off.
        viewModelScope.launch {
            if (!store.settings.first().autoUpdateCheck) return@launch
            val prefs = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("last_check", 0) < 24 * 3600_000L) return@launch
            prefs.edit().putLong("last_check", now).apply()
            val found = Updater.check(ctx)
            if (found != null) _message.value = "Laser Measure ${found.version} is available. Update it from the Device tab."
            else if (Updater.state.value is UpdateState.Failed) Updater.set(UpdateState.Idle) // stay quiet offline
        }
    }

    fun consumeMessage() { _message.value = null }

    fun bluetoothOn() = meter.scanner.isBluetoothOn
}
