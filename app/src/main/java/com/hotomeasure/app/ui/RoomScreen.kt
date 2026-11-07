package com.hotomeasure.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotomeasure.app.ble.LinkState
import com.hotomeasure.app.data.Corner
import com.hotomeasure.app.data.Pt
import com.hotomeasure.app.data.RoomMath
import com.hotomeasure.app.data.RoomResult
import com.hotomeasure.app.data.Settings
import com.hotomeasure.app.data.Units

@Composable
fun RoomPanel(vm: MainViewModel, s: Settings) {
    val d by vm.roomDraft.collectAsStateWithLifecycle()
    val target by vm.roomTarget.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val corners = Corner.decode(d.corners, d.walls.size)
    var finishing by remember { mutableStateOf(false) }

    Text(
        "Start in a corner and measure each wall in turn, going around the room. " +
            "If a corner sticks out into the room (around an alcove or chimney breast), tap it to make it Outside.",
        style = MaterialTheme.typography.bodySmall,
    )

    Card(Modifier.fillMaxWidth()) {
        if (d.walls.isEmpty()) {
            Text(
                if (state == LinkState.CONNECTED) "Measure the first wall." else "Connect the measure, then measure the first wall.",
                Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            FloorPlan(
                outline = RoomMath.trace(d.walls, corners),
                closed = false,
                labels = d.walls.map { Units.length(it, s) },
                highlight = (target as? RoomTarget.Wall)?.index,
                height = 240.dp,
            )
        }
    }

    d.walls.forEachIndexed { i, mm ->
        val selected = target == RoomTarget.Wall(i)
        Card(
            Modifier.fillMaxWidth().clickable { vm.selectRoomTarget(RoomTarget.Wall(i)) },
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wall ${i + 1}" + if (selected) "  ← measure again" else "", style = MaterialTheme.typography.labelLarge)
                    Text(Units.length(mm, s), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                FilterChip(
                    selected = corners[i] == Corner.OUTSIDE,
                    onClick = { vm.toggleCorner(i) },
                    label = { Text(if (corners[i] == Corner.OUTSIDE) "Outside corner" else "Inside corner") },
                )
            }
        }
    }
    if (target == RoomTarget.NextWall && d.walls.isNotEmpty()) {
        Text("Next reading: wall ${d.walls.size + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }

    // Optional ceiling height, for volume and wall area.
    Card(
        Modifier.fillMaxWidth().clickable { vm.selectRoomTarget(RoomTarget.Height) },
        border = if (target == RoomTarget.Height) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ceiling height (optional)" + if (target == RoomTarget.Height) "  ← next reading" else "",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(d.heightMm?.let { Units.length(it, s) } ?: "Tap, then measure", fontWeight = FontWeight.SemiBold)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = vm::undoWall, enabled = d.walls.isNotEmpty()) { Text("Undo wall") }
        OutlinedButton(onClick = vm::clearRoom, enabled = d.walls.isNotEmpty() || d.heightMm != null) { Text("Clear") }
        Button(onClick = { finishing = true }, enabled = d.walls.size >= 2) { Text("Finish room") }
    }

    if (finishing) FinishRoomDialog(vm, s, onDismiss = { finishing = false })
}

@Composable
private fun FinishRoomDialog(vm: MainViewModel, s: Settings, onDismiss: () -> Unit) {
    val d by vm.roomDraft.collectAsStateWithLifecycle()
    val result = remember(d) { RoomMath.solve(d.walls, Corner.decode(d.corners, d.walls.size)) }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result is RoomResult.Closed) "Room" else "The room doesn't close") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (result) {
                    is RoomResult.Closed -> {
                        FloorPlan(result.outline, closed = true, labels = result.walls.map { Units.length(it, s) }, highlight = null, height = 200.dp)
                        RoomFacts(result, d.heightMm, s)
                        if (result.calculatedLast) {
                            Text("The last wall (${Units.length(result.walls.last(), s)}) was calculated, not measured.", style = MaterialTheme.typography.bodySmall)
                        } else if (result.correctionMm >= 5) {
                            Text("Walls adjusted by up to ${Units.length(result.correctionMm.toInt(), s)} so the corners meet.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.cornerMismatch) {
                            Text(
                                "Check the corners: a room with square corners has 4 more inside corners than outside ones.",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Room name") }, placeholder = { Text("e.g. Living room") })
                    }
                    is RoomResult.Open -> {
                        FloorPlan(result.outline, closed = false, labels = d.walls.map { Units.length(it, s) }, highlight = null, height = 200.dp)
                        Text(
                            "The last wall ends ${if (result.gapMm.isNaN()) "away" else Units.length(result.gapMm.toInt(), s) + " away"} from the start. " +
                                "Check that every corner sticking out into the room is marked Outside, remeasure any wall that looks wrong, " +
                                "or measure the remaining walls.",
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (result is RoomResult.Closed) TextButton({ vm.saveRoom(name); onDismiss() }) { Text("Save room") }
            else TextButton(onDismiss) { Text("Keep measuring") }
        },
        dismissButton = { if (result is RoomResult.Closed) TextButton(onDismiss) { Text("Keep measuring") } },
    )
}

@Composable
fun RoomFacts(r: RoomResult.Closed, heightMm: Int?, s: Settings) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Floor area: ${Units.areaMm2(r.areaMm2, s)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Perimeter: ${Units.length(r.perimeterMm.toInt(), s)} · ${r.walls.size} walls")
        heightMm?.let { h ->
            Text("Ceiling: ${Units.length(h, s)}")
            Text("Volume: ${Units.volumeMm3(r.areaMm2 * h, s)}")
            Text("Wall area: ${Units.areaMm2(r.perimeterMm * h, s)} (before doors and windows)")
        }
    }
}

/**
 * Draws a room outline scaled to fit. Labels each wall with its length; an open outline shows a
 * dashed line from its end back to the start.
 */
@Composable
fun FloorPlan(outline: List<Pt>, closed: Boolean, labels: List<String>, highlight: Int?, height: Dp, showLabels: Boolean = true) {
    val wallColor = MaterialTheme.colorScheme.primary
    val hiColor = MaterialTheme.colorScheme.tertiary
    val gapColor = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val pts = if (closed) outline + outline.first() else outline

    Canvas(Modifier.fillMaxWidth().height(height).padding(if (showLabels) 28.dp else 6.dp)) {
        if (pts.size < 2) return@Canvas
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val w = (maxX - minX).coerceAtLeast(1.0); val h = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf(size.width / w, size.height / h)
        val ox = (size.width - w * scale) / 2; val oy = (size.height - h * scale) / 2
        // Plan coordinates have y pointing up; the screen's points down.
        fun map(p: Pt) = Offset((ox + (p.x - minX) * scale).toFloat(), (oy + (maxY - p.y) * scale).toFloat())

        val stroke = if (showLabels) 4.dp.toPx() else 2.dp.toPx()
        for (i in 0 until pts.size - 1) {
            drawLine(if (i == highlight) hiColor else wallColor, map(pts[i]), map(pts[i + 1]), strokeWidth = stroke * if (i == highlight) 1.6f else 1f)
        }
        if (!closed && pts.size > 2) {
            drawLine(gapColor, map(pts.last()), map(pts.first()), strokeWidth = stroke / 2, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        drawCircle(wallColor, radius = stroke * 1.4f, center = map(pts.first()))
        if (!closed) drawCircle(hiColor, radius = stroke * 1.4f, center = map(pts.last()), style = Stroke(stroke / 2))

        if (showLabels) {
            for (i in 0 until pts.size - 1) {
                val label = labels.getOrNull(i) ?: continue
                val a = map(pts[i]); val b = map(pts[i + 1])
                val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                val len = (b - a).getDistance().coerceAtLeast(1f)
                // Outward side of a counter-clockwise outline, in screen coordinates.
                val normal = Offset((b.y - a.y) / len, -(b.x - a.x) / len) * -1f
                val layout = measurer.measure(label, TextStyle(fontSize = 11.sp, color = textColor))
                val c = mid + normal * 14.dp.toPx()
                drawText(layout, topLeft = Offset(c.x - layout.size.width / 2, c.y - layout.size.height / 2))
            }
        }
    }
}
