package com.hotomeasure.app.ui

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.Switch
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotomeasure.app.ble.LinkState
import com.hotomeasure.app.ble.MeterConnection
import com.hotomeasure.app.data.DisplayUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(vm: MainViewModel, modifier: Modifier) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var permTick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permTick++ }
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val autoStartBusy by vm.autoStartBusy.collectAsStateWithLifecycle()
    val cdmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.autoStartDialogClosed(r.resultCode == Activity.RESULT_OK)
    }
    // Re-checked after each prompt returns and whenever the link state changes.
    val hasPerm = remember(permTick, state) { vm.meter.hasPermissions() }
    val btOn = remember(permTick, state) { vm.bluetoothOn() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Laser measure", style = MaterialTheme.typography.titleMedium)

        if (!hasPerm) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This app needs the Nearby devices permission to talk to the measure over Bluetooth. It doesn't use your location.")
                    Button(onClick = { permLauncher.launch(MeterConnection.PERMISSIONS) }) { Text("Allow Bluetooth") }
                }
            }
        } else if (!btOn) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bluetooth is off.")
                    Button(onClick = { btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
                }
            }
        }

        paired?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(p.address, style = MaterialTheme.typography.bodySmall)
                    Text("Status: ${state.label()}")
                    if (state == LinkState.NEEDS_REPAIR) {
                        Text(
                            "The measure no longer recognises this phone (it was probably paired with another app). Search below and pair it again.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (state != LinkState.CONNECTED && error != null) {
                        Text("Last problem: $error", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state == LinkState.SEARCHING) {
                        Text("Turn the measure on (hold the button) and keep it near the phone.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { vm.forget() }) { Text("Forget this measure") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Open when the measure turns on", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (autoStart) "On. Android opens this app when you switch the measure on."
                                else "Android will open this app whenever you switch the measure on.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (autoStartBusy) CircularProgressIndicator(Modifier.size(24.dp))
                        else Switch(
                            checked = autoStart,
                            onCheckedChange = { on ->
                                if (on) vm.enableAutoStart { sender -> cdmLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                                else vm.disableAutoStart()
                            },
                            enabled = pairing == null && !scanning,
                        )
                    }
                    if (!autoStart && !autoStartBusy) {
                        Text(
                            "To set it up, make sure the measure is on, then confirm the Android prompt.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (scanning) vm.stopScan() else vm.startScan() }, enabled = hasPerm && pairing == null) {
                Text(if (scanning) "Stop searching" else if (paired == null) "Search for measure" else "Pair a measure again")
            }
            if (scanning) CircularProgressIndicator(Modifier.size(24.dp))
        }
        if (paired == null && !scanning && found.isEmpty()) {
            Text(
                "Turn the measure on, then tap Search. Pairing replaces any pairing the measure had with Mi Home or another app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        found.forEach { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.SemiBold)
                        Text("${f.address} · signal ${f.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    if (pairing == f.address) CircularProgressIndicator(Modifier.size(24.dp))
                    else Button(onClick = { vm.pair(f) }, enabled = pairing == null) { Text("Pair") }
                }
            }
        }

        HorizontalDivider()
        Text("Units", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayUnit.entries.forEach { u ->
                FilterChip(selected = s.unit == u, onClick = { vm.updateSettings { it.copy(unit = u) } }, label = { Text(u.label) })
            }
        }
        if (s.unit == DisplayUnit.FT_IN || s.unit == DisplayUnit.IN) {
            Text("Round inches to", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 8, 16, 32).forEach { f ->
                    FilterChip(selected = s.inchFraction == f, onClick = { vm.updateSettings { it.copy(inchFraction = f) } },
                        label = { Text(if (f == 1) "1\"" else "1/$f\"") })
                }
            }
        } else if (s.unit == DisplayUnit.M || s.unit == DisplayUnit.FT) {
            Text("Decimal places", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3).forEach { d ->
                    FilterChip(selected = s.decimals == d, onClick = { vm.updateSettings { it.copy(decimals = d) } }, label = { Text("$d") })
                }
            }
        }
        Text(
            "The measure's own screen switches between metres and feet to match.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
