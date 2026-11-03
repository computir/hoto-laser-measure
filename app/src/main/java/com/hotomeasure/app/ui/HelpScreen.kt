package com.hotomeasure.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val SOURCE_URL = "https://github.com/computir/hoto-laser-measure"
private const val ZAMER_URL = "https://github.com/yelloduxx/zamer"

private class Section(val title: String, val points: List<String>)

private val sections = listOf(
    Section(
        "Getting started",
        listOf(
            "Turn the measure on by pressing its button.",
            "Open the Device tab and tap Allow Bluetooth. The app uses the \"Nearby devices\" permission and never your location.",
            "Tap Search for measure, then Pair next to the measure (it appears as xiaojiang.cc_…).",
            "The measure remembers one phone or app at a time. Pairing here replaces a pairing with Mi Home.",
        ),
    ),
    Section(
        "Taking measurements",
        listOf(
            "Wait until the chip at the top says Connected, then press the button on the measure. The reading appears in the app.",
            "In Distance mode every reading is saved to History automatically.",
            "The smaller line under the reading shows the same length in the other unit system.",
        ),
    ),
    Section(
        "Area and volume",
        listOf(
            "Choose Area (2 sides) or Volume (3 sides). Each reading fills the side marked \"← next\".",
            "Tap a side to measure it again.",
            "When every side is filled in, tap Save to history. Clear starts over.",
        ),
    ),
    Section(
        "Measuring from the front or back edge",
        listOf(
            "Back edge (the default) measures from the bottom of the measure, which is handy against a wall.",
            "Front edge measures from the laser window. The setting is stored on the measure itself.",
        ),
    ),
    Section(
        "Units",
        listOf(
            "Choose m, cm, mm, ft-in, ft or in on the Device tab. For ft-in and in you can round to 1/16\" or 1/32\", and so on.",
            "The measure's own screen switches between metres and feet to match the app.",
        ),
    ),
    Section(
        "History",
        listOf(
            "Use the pencil to name a measurement (for example, \"Kitchen wall\"), the copy icon to copy it, and the bin to delete it.",
            "Share all sends every measurement as text, to email or Notes for example.",
            "History stays on this phone. It is kept if you pair again.",
        ),
    ),
    Section(
        "Opening automatically",
        listOf(
            "On the Device tab, turn on Open when the measure turns on (the measure must be on), then confirm Android's prompt.",
            "From then on, switching the measure on opens this app, even when it is closed. If the phone is locked, the app is waiting when you unlock it.",
            "For 30 seconds after the app disconnects it won't reopen itself, so closing the app doesn't make it pop back up.",
        ),
    ),
    Section(
        "Staying connected and power",
        listOf(
            "While connected, the screen stays on so the phone doesn't sleep in the middle of a job.",
            "If you lock the phone or switch apps, the connection stays up for 1 minute. Readings taken during that minute are still saved.",
            "The measure switches itself off after about 3 minutes without a button press. To switch it off sooner, hold its button for 3 seconds.",
        ),
    ),
    Section(
        "Updates",
        listOf(
            "The Device tab shows your version. Tap Check for updates to look for a newer release on GitHub.",
            "Tap Download and install, then confirm Android's prompt. The first time, Android asks you to allow Laser Measure to install apps.",
            "Your history, settings and pairing are kept. The app reopens by itself when the update finishes.",
            "With Advanced Protection on (or a work policy), Android doesn't let any app install updates. The app still tells you when a new version is out. Install it from a computer: plug the phone in and run scripts\\update-phone.ps1 from the project.",
            "Before anything is installed, each download is checked against GitHub's published checksum and has to be signed by the same key as this app.",
            "By default the app checks about once a day. You can turn that off on the Device tab.",
        ),
    ),
    Section(
        "Troubleshooting",
        listOf(
            "\"Pair again\": something else, such as Mi Home, paired with the measure. Search and pair again; your history is kept.",
            "The measure isn't found: make sure it's on and close to the phone, and close Mi Home and any other app that might be connected to it. The measure can only be found while nothing is connected to it.",
            "\"Bluetooth off\" or \"Needs permission\": turn Bluetooth on, or allow Nearby devices on the Device tab.",
            "It keeps searching: turn the measure off and on again. The Device tab shows the last problem the app ran into.",
        ),
    ),
)

@Composable
fun HelpScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    s.points.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Laser Measure $version", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Free and open source under the Apache License 2.0. No account and no tracking. The only internet use is checking GitHub for updates; measuring works fully offline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "The Bluetooth protocol is based on the work of the Zamer project. This app is independent and is not affiliated with HOTO or Xiaomi.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { open(SOURCE_URL) }) { Text("Source code on GitHub") }
                TextButton(onClick = { open(ZAMER_URL) }) { Text("Zamer project") }
            }
        }
    }
}
