package com.nayproductions.flightview.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nayproductions.flightview.Accent
import com.nayproductions.flightview.R

@Composable
fun OnboardingScreen(deniedBefore: Boolean, onContinue: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.FlightTakeoff, contentDescription = null, tint = Accent, modifier = Modifier.size(56.dp))
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Reason(Icons.Filled.CameraAlt, R.string.reason_camera_title, R.string.reason_camera)
            Reason(Icons.Filled.LocationOn, R.string.reason_location_title, R.string.reason_location)
            Reason(Icons.Filled.SettingsInputAntenna, R.string.reason_data_title, R.string.reason_data)
            Spacer(Modifier.weight(1f))
            if (deniedBefore) {
                Text(stringResource(R.string.permissions_needed), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, @StringRes title: Int, @StringRes body: Int) {
    Row(Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = null, tint = Accent)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
