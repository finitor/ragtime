package org.ragtime.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** One gear of the ladder, most robust first: what it moves, what it needs, and how far short the path is. */
internal data class GearRow(val mode: String, val label: String, val speed: String, val needs: String, val perKb: String,
    val width: Int, val short: String, val current: Boolean)

/**
 * The gearshift as the engines have it (platform/android/AndroidGear.cpp):
 * the mode broadcasts go in. The gears come from the thresholds
 * data2g-host gives with its modes; a host that gives none has no gears.
 */
internal data class GearState(val auto: Boolean = true,
    /** Auto is on and no station has been heard: no gear yet, and sending is in the modem's own mode. */
    val waiting: Boolean = false, val path: String = "awgn", val pathLabel: String = "Calm",
    val hostThresholds: Boolean = false, val mode: String = "", val label: String = "", val speed: String = "", val bps: Long = 0,
    val snr: String = "", val snrFrom: String = "", val snrBursts: Int = 0,
    val paths: List<Pair<String, String>> = emptyList(), val ladder: List<GearRow> = emptyList()) {
    /** "about 1 min 10 s" for this many bytes in the gear in effect; empty with no gear. */
    fun airtime(bytes: Int): String = if(bps <= 0) "" else "about " + duration(bytes * 8.0 / bps)

    companion object {
        fun parse(j: JSONObject?): GearState {
            if(j == null) return GearState()
            val paths = j.optJSONArray("paths"); val ladder = j.optJSONArray("ladder")
            return GearState(j.optBoolean("auto", true), j.optBoolean("waiting"), j.optString("path", "awgn"), j.optString("pathLabel", "Calm"), j.optBoolean("hostThresholds"),
                j.optString("mode"), j.optString("label"), j.optString("speed"), j.optLong("bps"), j.optString("snr"), j.optString("snrFrom"), j.optInt("snrBursts"),
                if(paths == null) emptyList() else (0 until paths.length()).map { paths.getJSONObject(it).let { p -> p.getString("key") to p.getString("label") } },
                if(ladder == null) emptyList() else (0 until ladder.length()).map { ladder.getJSONObject(it).let { g ->
                    GearRow(g.getString("mode"), g.getString("label"), g.getString("speed"), g.getString("needs"), g.getString("perKb"), g.optInt("width"), g.optString("short"), g.optBoolean("current"))
                } })
        }
        /** "8 s", "1 min 10 s", "12 min": to the second below ten minutes, to the minute above. */
        fun duration(seconds: Double): String {
            val whole = maxOf(1, Math.round(seconds).toInt())
            return when {
                whole < 60 -> "$whole s"
                whole < 600 -> "${whole / 60} min ${whole % 60} s"
                else -> "${(whole + 30) / 60} min"
            }
        }
    }
}

/** The gear in effect, on Field desk: a tap opens the gearshift. */
@Composable
internal fun GearButton(gear: GearState, ready: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().testTag("gear-button")) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(when {
                    gear.label.isNotEmpty() -> gear.label
                    gear.waiting -> "Gearshift"
                    !ready -> "Gearshift"
                    else -> "No gears"
                }, fontWeight = FontWeight.Bold)
                Text(when {
                    gear.label.isNotEmpty() -> gear.speed + (if(gear.snr.isNotEmpty()) " · weakest path ${gear.snr}" else "")
                    gear.waiting -> "The modem's own mode until a station is heard"
                    !ready -> "Connect to see the gears"
                    else -> "The modem gave no thresholds; sending in its own mode"
                }, style = MaterialTheme.typography.bodySmall)
            }
            if(gear.label.isNotEmpty() || gear.waiting) Text(if(gear.auto) "Auto" else "By hand", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GearSheet(model: RadioModel, onDismiss: () -> Unit) {
    val state = model.snapshot
    val gear = state.gear
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().testTag("gear-list"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Gearshift", style = MaterialTheme.typography.headlineSmall) }
            item { Text("The gear is the mode your broadcasts, files and pictures on D2G go in: lower gears get through on weaker paths, higher ones are faster. A ragchew's speed is set by the modem.",
                style = MaterialTheme.typography.bodySmall) }
            if(gear.ladder.isEmpty()) item {
                Text(if(!state.ready) "Connect to see the gears."
                     else "The modem gave no thresholds with its modes, so there are no gears to choose from: Ragtime sends in the mode the modem is set to. Data2G gives thresholds from version 0.5.1.")
            } else {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Auto")
                        Text(if(gear.snr.isNotEmpty()) "Going by ${gear.snrFrom}, the weakest station heard lately: ${gear.snr} over ${gear.snrBursts} burst${if(gear.snrBursts == 1) "" else "s"}, with 3 dB to spare."
                             else "Picks the fastest gear the weakest station heard lately can copy. No station has been heard yet, so for now Ragtime sends in the mode the modem is set to.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = gear.auto, onCheckedChange = { model.action("gear-auto", it.toString()) }, modifier = Modifier.testTag("gear-auto"))
                } }
                item { Text("Path", style = MaterialTheme.typography.labelMedium) }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for((key, label) in gear.paths) FilterChip(selected = gear.path == key, onClick = { model.action("gear-path", key) }, label = { Text(label) })
                } }
                item { Text("Gears, most robust first. Tap one to stay in it.", style = MaterialTheme.typography.labelMedium) }
                items(gear.ladder, key = { it.mode }) { row ->
                    Surface(color = if(row.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().clickable { model.action("gear", row.mode) }.testTag("gear-row")) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.label, fontWeight = FontWeight.Medium)
                                Text("${row.speed} · needs ${row.needs} · ${row.perKb} per KB", style = MaterialTheme.typography.bodySmall)
                                Text(row.mode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(row.current) Text("In use", style = MaterialTheme.typography.labelLarge)
                            else if(row.short.isNotEmpty()) Text("${row.short} short", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
