package com.nayproductions.flightview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nayproductions.flightview.Accent
import com.nayproductions.flightview.R
import com.nayproductions.flightview.camera.CameraPreview
import com.nayproductions.flightview.core.SkyMarker
import com.nayproductions.flightview.sensors.OrientationSensor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val LabelW = 132.dp
private val LabelH = 40.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyScreen(vm: SkyViewModel = viewModel()) {
    LifecycleResumeEffect(Unit) {
        vm.start()
        onPauseOrDispose { vm.stop() }
    }

    val status by vm.status.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val attribution by vm.attribution.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val nudge by vm.headingNudgeDeg.collectAsStateWithLifecycle()
    var calibrating by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showLogbook by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(Modifier.fillMaxSize()) { vm.optics = it }
        SkyOverlay(vm, calibrating, nudge)

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            val message = when {
                status == SkyStatus.LOCATING -> stringResource(R.string.status_locating)
                !vm.sensorsAvailable -> stringResource(R.string.status_no_sensors)
                status == SkyStatus.BUSY -> stringResource(R.string.status_busy)
                status == SkyStatus.ERROR -> stringResource(R.string.status_error)
                calibration == OrientationSensor.Calibration.POOR -> stringResource(R.string.status_calibrate)
                else -> null
            }
            message?.let { Pill(it) }
            Spacer(Modifier.weight(1f))
            if (calibrating) {
                CalibrationBar(nudge, onReset = { vm.setNudge(0.0) }, onDone = { calibrating = false })
                Spacer(Modifier.size(12.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                RoundButton(Icons.AutoMirrored.Filled.List, stringResource(R.string.cd_list)) { showList = true }
                RoundButton(Icons.Filled.GpsFixed, stringResource(R.string.cd_calibrate)) { calibrating = !calibrating }
                RoundButton(Icons.Filled.Book, stringResource(R.string.cd_logbook)) { showLogbook = true }
            }
            if (attribution.isNotEmpty()) {
                Text(
                    stringResource(R.string.data_attribution, attribution.joinToString(" · ")),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    selectedId?.let { id ->
        ModalBottomSheet(onDismissRequest = { vm.select(null) }) { DetailSheet(vm, id) }
    }
    if (showList) {
        ModalBottomSheet(onDismissRequest = { showList = false }) {
            NearbySheet(vm) { id ->
                showList = false
                vm.select(id)
            }
        }
    }
    if (showLogbook) {
        ModalBottomSheet(onDismissRequest = { showLogbook = false }) { LogbookSheet() }
    }
}

@Composable
private fun SkyOverlay(vm: SkyViewModel, calibrating: Boolean, nudge: Double) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        SideEffect {
            vm.labelW = with(density) { LabelW.toPx() }
            vm.labelH = with(density) { LabelH.toPx() }
            vm.tapRadiusPx = with(density) { 44.dp.toPx() }
        }
        var frame by remember { mutableStateOf<SkyFrame?>(null) }
        LaunchedEffect(wPx, hPx) {
            while (true) withFrameNanos { frame = vm.frame(wPx, hPx) }
        }
        val selectedId by vm.selectedId.collectAsStateWithLifecycle()
        val f = frame

        Box(
            Modifier
                .fillMaxSize()
                .clearAndSetSemantics {} // the list view is the accessible way in
                .pointerInput(calibrating) {
                    if (calibrating) {
                        var start = nudge
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { start = vm.headingNudgeDeg.value; dx = 0f },
                        ) { _, amount ->
                            dx += amount
                            // Dragging right moves labels right: ~30 degrees per screen width.
                            vm.setNudge(start - dx / wPx * 30.0)
                        }
                    } else {
                        detectTapGestures { p -> frame?.let { vm.select(p.x, p.y, it) } }
                    }
                },
        ) {
            if (f == null) {
                val vmStatus by vm.status.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Pill(stringResource(if (vmStatus == SkyStatus.LOCATING) R.string.status_locating else R.string.status_hold_up))
                }
            } else {
                SkyMarkers(f, selectedId)
            }
        }
    }
}

@Composable
private fun SkyMarkers(f: SkyFrame, selectedId: String?) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            for (m in f.markers) {
                val p = f.smoothed[m.aircraft.id]
                val alpha = if (m.isStale) 0.4f else 1f
                if (p != null) {
                    val r = (if (m.aircraft.id == selectedId) 9.dp else 6.dp).toPx()
                    drawCircle(Color.White.copy(alpha = 0.95f * alpha), r, Offset(p.first, p.second), style = Stroke(2.dp.toPx()))
                    drawCircle(Accent.copy(alpha = alpha), r - 3.dp.toPx(), Offset(p.first, p.second))
                } else if (m.look.slantRangeM < 40_000) {
                    drawEdgeArrow(size.width, size.height, m.edgeAngleRad, inset = 28.dp.toPx())
                }
            }
        }
        for (label in f.labels) {
            val m = f.markers.firstOrNull { it.aircraft.id == label.id } ?: continue
            MarkerLabel(m, Modifier.offset { IntOffset(label.left.roundToInt(), label.top.roundToInt()) })
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdgeArrow(w: Float, h: Float, angle: Double, inset: Float) {
    val cx = w / 2
    val cy = h / 2
    val dx = cos(angle).toFloat()
    val dy = -sin(angle).toFloat()
    val sx = if (abs(dx) < 1e-6f) Float.MAX_VALUE else (cx - inset) / abs(dx)
    val sy = if (abs(dy) < 1e-6f) Float.MAX_VALUE else (cy - inset) / abs(dy)
    val s = min(sx, sy)
    val tip = Offset(cx + dx * s, cy + dy * s)
    val back = Offset(tip.x - dx * 40f, tip.y - dy * 40f)
    val n = Offset(-dy * 20f, dx * 20f)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + n.x, back.y + n.y)
        lineTo(back.x - n.x, back.y - n.y)
        close()
    }
    drawPath(path, Color.White.copy(alpha = 0.7f))
}

@Composable
private fun MarkerLabel(m: SkyMarker, modifier: Modifier) {
    Column(
        modifier
            .size(LabelW, LabelH)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        val alpha = if (m.isStale) 0.55f else 1f
        Text(
            m.aircraft.displayName, color = Color.White.copy(alpha = alpha), fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${Fmt.altitude(m.position.heightM)} · ${Fmt.distance(m.look.slantRangeM)}",
            color = Color.White.copy(alpha = 0.75f * alpha), style = MaterialTheme.typography.labelSmall, maxLines = 1,
        )
    }
}

@Composable
fun Pill(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(56.dp), shape = CircleShape) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun CalibrationBar(nudge: Double, onReset: () -> Unit, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Text(stringResource(R.string.calibrate_hint), color = Color.White, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calibrate_offset, nudge), color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onReset) { Text(stringResource(R.string.action_reset)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
        }
    }
}
