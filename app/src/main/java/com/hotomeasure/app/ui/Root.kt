package com.hotomeasure.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotomeasure.app.ble.LinkState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: MainViewModel) {
    val paired by vm.paired.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    // First run: go straight to the device tab so the person can pair.
    LaunchedEffect(paired == null) { if (paired == null) tab = 2 }
    // Keep the screen awake while connected, so the phone doesn't sleep (and drop the measure) mid-job.
    val view = LocalView.current
    val connected = state == LinkState.CONNECTED
    DisposableEffect(connected) {
        view.keepScreenOn = connected
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    var showHelp by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showHelp) { showHelp = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showHelp) "Help" else listOf("Laser Measure", "History", "Device & settings")[tab]) },
                navigationIcon = {
                    if (showHelp) IconButton(onClick = { showHelp = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (!showHelp) {
                        StatusChip(state) { tab = 2 }
                        IconButton(onClick = { showHelp = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Help") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                // Tapping a tab also leaves Help.
                NavigationBarItem(tab == 0 && !showHelp, { tab = 0; showHelp = false }, { Icon(Icons.Default.Straighten, null) }, label = { Text("Measure") })
                NavigationBarItem(tab == 1 && !showHelp, { tab = 1; showHelp = false }, { Icon(Icons.Default.History, null) }, label = { Text("History") })
                NavigationBarItem(tab == 2 && !showHelp, { tab = 2; showHelp = false }, { Icon(Icons.Default.Bluetooth, null) }, label = { Text("Device") })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        val m = Modifier.padding(pad)
        if (showHelp) HelpScreen(m) else when (tab) {
            0 -> MeasureScreen(vm, m)
            1 -> HistoryScreen(vm, m)
            else -> DeviceScreen(vm, m)
        }
    }
}

fun LinkState.label() = when (this) {
    LinkState.NOT_PAIRED -> "Not paired"
    LinkState.NEEDS_PERMISSION -> "Needs permission"
    LinkState.BLUETOOTH_OFF -> "Bluetooth off"
    LinkState.SEARCHING -> "Searching…"
    LinkState.CONNECTING -> "Connecting…"
    LinkState.CONNECTED -> "Connected"
    LinkState.NEEDS_REPAIR -> "Pair again"
}
