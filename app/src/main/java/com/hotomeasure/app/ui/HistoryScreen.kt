package com.hotomeasure.app.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotomeasure.app.data.Corner
import com.hotomeasure.app.data.Measurement
import com.hotomeasure.app.data.RoomMath
import com.hotomeasure.app.data.RoomResult
import com.hotomeasure.app.data.Settings
import com.hotomeasure.app.data.Units
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun Measurement.room(): RoomResult.Closed? =
    RoomMath.solve(mm, Corner.decode(corners, mm.size)) as? RoomResult.Closed

fun Measurement.result(s: Settings): String = when (kind) {
    "room" -> room()?.let { Units.areaMm2(it.areaMm2, s) } ?: "Room"
    "area" -> Units.area(mm[0], mm[1], s)
    "volume" -> Units.volume(mm[0], mm[1], mm[2], s)
    else -> Units.length(mm[0], s)
}

fun Measurement.detail(s: Settings): String =
    if (kind == "room") buildString {
        val r = room()
        append("${mm.size} walls")
        r?.let { append(" · perimeter ").append(Units.length(it.perimeterMm.toInt(), s)) }
        heightMm?.let { h ->
            append(" · ceiling ").append(Units.length(h, s))
            r?.let { append(" · ").append(Units.volumeMm3(it.areaMm2 * h, s)) }
        }
        append("\nWalls: ").append(mm.joinToString(", ") { Units.length(it, s) })
    } else if (mm.size > 1) mm.joinToString(" × ") { Units.length(it, s) } else Units.alternate(mm[0], s)

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier) {
    val list by vm.history.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Measurement?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    fun line(m: Measurement) = buildString {
        if (m.label.isNotEmpty()) append(m.label).append(": ")
        append(m.result(s))
        if (m.kind == "room") append(" (").append(m.detail(s).replace("\n", "; ")).append(")")
        else if (m.mm.size > 1) append(" (").append(m.detail(s)).append(")")
    }

    if (list.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No measurements yet. Readings from the measure show up here.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val text = list.joinToString("\n") { "${fmt.format(Date(it.time))}\t${it.kind}\t${line(it)}" }
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, "Measurements")
                    }, "Share measurements"))
                }) { Icon(Icons.Default.Share, null); Text("  Share all") }
                OutlinedButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, null); Text("  Clear") }
            }
        }
        items(list, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (m.label.isNotEmpty()) Text(m.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(m.result(s), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(m.detail(s), style = MaterialTheme.typography.bodyMedium)
                        if (m.kind == "room") m.room()?.let { r ->
                            FloorPlan(r.outline, closed = true, labels = emptyList(), highlight = null, height = 110.dp, showLabels = false)
                        }
                        Text("${m.kind.replaceFirstChar { it.uppercase() }} · ${fmt.format(Date(m.time))}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("measurement", line(m)))) } }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                    IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, "Name") }
                    IconButton(onClick = { vm.delete(m.id) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.label) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Name this measurement") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("e.g. Kitchen wall") }) },
            confirmButton = { TextButton({ vm.rename(m.id, text); editing = null }) { Text("Save") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes ${list.size} saved measurements.") },
            confirmButton = { TextButton({ vm.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        )
    }
}
