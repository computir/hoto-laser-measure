package com.hotomeasure.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotomeasure.app.ble.Edge
import com.hotomeasure.app.ble.LinkState
import com.hotomeasure.app.data.Units

@Composable
fun StatusChip(state: LinkState, onClick: () -> Unit) {
    val (icon, tint) = when (state) {
        LinkState.CONNECTED -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        LinkState.SEARCHING, LinkState.CONNECTING -> Icons.Default.Sync to MaterialTheme.colorScheme.tertiary
        LinkState.NOT_PAIRED -> Icons.Default.Circle to MaterialTheme.colorScheme.outline
        else -> Icons.Default.WarningAmber to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = onClick,
        label = { Text(state.label()) },
        leadingIcon = { Icon(icon, null, tint = tint, modifier = Modifier.padding(0.dp)) },
        colors = AssistChipDefaults.assistChipColors(),
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
fun MeasureScreen(vm: MainViewModel, modifier: Modifier) {
    val ws by vm.workspace.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Mode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = ws.mode == m,
                    onClick = { vm.setMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, Mode.entries.size),
                ) { Text(m.label) }
            }
        }

        // The big reading.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val last = ws.last
                Text(
                    if (last == null) "—" else Units.length(last, s),
                    fontSize = 56.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (last != null) {
                    Text(Units.alternate(last, s), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        state != LinkState.CONNECTED -> "Measure not connected — ${state.label()}"
                        else -> "Press the button on the measure"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        if (ws.mode != Mode.DISTANCE) {
            Text("Tap a side to measure it again", style = MaterialTheme.typography.labelLarge)
            ws.mode.sides.forEachIndexed { i, name ->
                val v = ws.sides.getOrNull(i)
                val selected = ws.target == i
                Card(
                    Modifier.fillMaxWidth().clickable { vm.selectSide(i) },
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name + if (selected) "  ← next" else "", style = MaterialTheme.typography.titleMedium)
                        Text(v?.let { Units.length(it, s) } ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            val done = ws.sides.all { it != null }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(ws.mode.label, style = MaterialTheme.typography.titleLarge)
                    val sides = ws.sides
                    Text(
                        if (!done) "—" else if (ws.mode == Mode.AREA) Units.area(sides[0]!!, sides[1]!!, s)
                        else Units.volume(sides[0]!!, sides[1]!!, sides[2]!!, s),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = vm::clearSides) { Text("Clear") }
                Button(onClick = { vm.saveResult() }, enabled = done) { Text("Save to history") }
            }
        } else {
            Text("Every distance reading is saved to History automatically.", style = MaterialTheme.typography.bodySmall)
        }

        Text("Measure from", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(Edge.BACK to "Back edge", Edge.FRONT to "Front edge").forEachIndexed { i, (e, label) ->
                SegmentedButton(
                    selected = status.edge == e,
                    onClick = { vm.setEdge(e) },
                    enabled = state == LinkState.CONNECTED,
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
    }
}
