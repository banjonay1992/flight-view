package com.nayproductions.flightview.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nayproductions.flightview.FlightViewApp
import com.nayproductions.flightview.R
import com.nayproductions.flightview.core.SkyMarker
import com.nayproductions.flightview.data.Spot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DetailSheet(vm: SkyViewModel, id: String) {
    // Refresh once a second so distance and altitude stay live while the sheet is open.
    val marker by produceState<SkyMarker?>(vm.snapshot(id), id) {
        while (true) {
            value = vm.snapshot(id)
            delay(1_000)
        }
    }
    var saved by remember(id) { mutableStateOf(false) }
    var saving by remember(id) { mutableStateOf(false) }
    val m = marker
    if (m == null) {
        Text(stringResource(R.string.detail_gone), modifier = Modifier.padding(24.dp))
        return
    }
    val a = m.aircraft
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.semantics(mergeDescendants = true) {}.padding(bottom = 12.dp)) {
                Text(a.displayName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                a.operator?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                (a.typeName ?: a.typeCode)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { Header(stringResource(R.string.detail_where)) }
        item { DetailRow(stringResource(R.string.detail_distance), Fmt.distance(m.look.slantRangeM)) }
        item { DetailRow(stringResource(R.string.detail_altitude), Fmt.altitude(m.position.heightM)) }
        item {
            DetailRow(
                stringResource(R.string.detail_direction),
                "${Fmt.compass(m.look.azimuthDeg)}, ${stringResource(R.string.degrees_up, m.look.elevationDeg.roundToInt())}",
            )
        }
        a.groundSpeedKt?.let { item { DetailRow(stringResource(R.string.detail_speed), Fmt.speed(it)) } }
        a.trackDeg?.let { item { DetailRow(stringResource(R.string.detail_heading), Fmt.compass(it)) } }
        item { Header(stringResource(R.string.detail_aircraft)) }
        a.registration?.let { item { DetailRow(stringResource(R.string.detail_registration), it) } }
        a.typeCode?.let { item { DetailRow(stringResource(R.string.detail_type), it) } }
        item { DetailRow(stringResource(R.string.detail_icao), a.id.uppercase()) }
        a.squawk?.let { item { DetailRow(stringResource(R.string.detail_squawk), it) } }
        item { DetailRow(stringResource(R.string.detail_age), "${m.positionAgeS.roundToInt()} s") }
        item {
            Button(
                onClick = {
                    saving = true
                    vm.save(m) { ok ->
                        saved = ok
                        saving = false
                    }
                },
                enabled = !saved && !saving,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                Text(stringResource(if (saved) R.string.detail_saved else R.string.detail_save))
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    HorizontalDivider()
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Accessible, non-visual way to use the app: nearest first, with clock-face directions. */
@Composable
fun NearbySheet(vm: SkyViewModel, onSelect: (String) -> Unit) {
    val items by produceState(vm.nearby()) {
        while (true) {
            value = vm.nearby()
            delay(2_000)
        }
    }
    Column(Modifier.padding(horizontal = 24.dp).heightIn(min = 200.dp)) {
        Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.headlineSmall)
        if (items.isEmpty()) {
            Text(stringResource(R.string.nearby_empty), modifier = Modifier.padding(vertical = 24.dp))
        }
        val heading = vm.cameraHeadingDeg
        LazyColumn {
            items(items, key = { it.aircraft.id }) { m ->
                val dir = heading?.let { stringResource(R.string.clock, Fmt.clockHour(m.look.azimuthDeg - it)) }
                    ?: Fmt.compass(m.look.azimuthDeg)
                val summary = listOfNotNull(
                    m.aircraft.typeName ?: m.aircraft.typeCode,
                    Fmt.distance(m.look.slantRangeM),
                    dir,
                    stringResource(R.string.degrees_up, m.look.elevationDeg.roundToInt()),
                ).joinToString(" · ")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(m.aircraft.id) }
                        .semantics(mergeDescendants = true) {}
                        .padding(vertical = 12.dp),
                ) {
                    Text(m.aircraft.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun LogbookSheet() {
    val logbook = (LocalContext.current.applicationContext as FlightViewApp).container.logbook
    val spots = remember { mutableStateListOf<Spot>() }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { logbook.recent() }
            .onSuccess { spots.clear(); spots.addAll(it) }
            .onFailure { failed = true }
        loading = false
    }

    Column(Modifier.padding(horizontal = 24.dp).heightIn(min = 240.dp)) {
        Text(stringResource(R.string.logbook_title), style = MaterialTheme.typography.headlineSmall)
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            failed -> Text(stringResource(R.string.logbook_error), modifier = Modifier.padding(vertical = 24.dp))
            spots.isEmpty() -> Text(stringResource(R.string.logbook_empty), modifier = Modifier.padding(vertical = 24.dp))
            else -> LazyColumn {
                items(spots, key = { it.id }) { s ->
                    val state = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v == SwipeToDismissBoxValue.EndToStart) {
                                spots.remove(s)
                                scope.launch { runCatching { logbook.delete(s) } }
                                true
                            } else {
                                false
                            }
                        },
                    )
                    SwipeToDismissBox(state, backgroundContent = {}, enableDismissFromStartToEnd = false) {
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 12.dp)) {
                            Text(s.callsign ?: s.registration ?: s.icao24.uppercase(), style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(s.typeName ?: s.typeCode, s.operator).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(s.spottedAt.take(16).replace('T', ' '), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
