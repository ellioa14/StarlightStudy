package com.example.aurorascout

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: AuroraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF73FBD3), background = Color(0xFF071018), surface = Color(0xFF101C27))) {
                AuroraScoutApp(viewModel)
            }
        }
    }
}

@Composable
private fun AuroraScoutApp(vm: AuroraViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasPermission = remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission.value = result.values.any { it }
        if (hasPermission.value) vm.refreshFromGps()
    }

    LaunchedEffect(hasPermission.value) {
        if (hasPermission.value && state.location == null && !state.isLoading) vm.refreshFromGps()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("AuroraScout", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Best northern-lights times and nearby viewing spots from your GPS location.", color = Color(0xFFB7C8D4))
            }

            if (!hasPermission.value) {
                item {
                    Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                        Text("Use my location")
                    }
                }
            } else {
                item {
                    Button(onClick = vm::refreshFromGps, enabled = !state.isLoading) { Text(if (state.isLoading) "Updating…" else "Refresh forecast") }
                }
            }

            state.location?.let { point ->
                item { InfoCard("Your location", "${"%.3f".format(point.latitude)}, ${"%.3f".format(point.longitude)}") }
            }

            state.liveAurora?.let { live ->
                item { InfoCard("Live aurora probability", "${live.probabilityPercent}% near your location") }
            }

            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { Text(state.locationMessage, color = Color(0xFFB7C8D4)) }

            if (state.nights.isNotEmpty()) {
                item { Text("Next 7 nights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                items(state.nights) { night ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(night.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), fontWeight = FontWeight.SemiBold)
                            Text("Aurora score: ${night.score}/100")
                            Text("Best time: ${night.bestTime?.toLocalTime()?.toString() ?: "No dark window"}")
                            Text("Kp ${"%.1f".format(Locale.US, night.kp)} • Clouds ${night.cloudCover}% • ${night.confidence} confidence", color = Color(0xFFB7C8D4))
                        }
                    }
                }
            }

            if (state.places.isNotEmpty()) {
                item { Text("Nearby viewing spots", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                items(state.places) { place ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(place.name, fontWeight = FontWeight.SemiBold)
                            Text("${place.category} • ${place.distanceKm.roundToInt()} km away • score ${place.score}/100")
                            Text("Clouds ${place.cloudCover}% • darkness ${place.darknessScore}/100", color = Color(0xFFB7C8D4))
                            OutlinedButton(onClick = {
                                val uri = Uri.parse("geo:${place.point.latitude},${place.point.longitude}?q=${place.point.latitude},${place.point.longitude}(${Uri.encode(place.name)})")
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }) { Text("Open on map") }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Data: NOAA SWPC + Open-Meteo + OpenStreetMap/Overpass. Viewing scores are estimates, not guarantees.", color = Color(0xFF78909C), style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, color = Color(0xFFB7C8D4))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
