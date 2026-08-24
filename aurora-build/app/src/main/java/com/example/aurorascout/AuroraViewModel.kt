package com.example.aurorascout

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class AuroraViewModel(application: Application) : AndroidViewModel(application) {
    private val locationClient = LocationClient(application)
    private val network = AuroraNetwork()
    private val _ui = MutableStateFlow(AuroraUiState())
    val ui: StateFlow<AuroraUiState> = _ui.asStateFlow()

    fun refreshFromGps() {
        if (_ui.value.isLoading) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(isLoading = true, error = null, locationMessage = "Getting a GPS fix…")
            try {
                val point = locationClient.getCurrentLocation()
                _ui.value = _ui.value.copy(location = point, locationMessage = "Building local sky forecast…")
                load(point)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(isLoading = false, error = e.message ?: "Location failed", locationMessage = "Could not get your location.")
            }
        }
    }

    private suspend fun load(point: GeoPoint) = coroutineScope {
        try {
            val weatherD = async { network.fetchWeather(point) }
            val kpD = async { network.fetchKpForecast() }
            val outlookD = async { network.fetch27DayOutlook() }
            val liveD = async { runCatching { network.fetchLiveAurora(point) }.getOrNull() }

            val weather = weatherD.await()
            val kp = kpD.await()
            val outlook = outlookD.await()
            val live = liveD.await()
            val nights = AuroraScoring.buildCalendar(point.latitude, weather, kp, outlook)

            _ui.value = _ui.value.copy(
                zoneId = weather.zoneId,
                liveAurora = live,
                nights = nights,
                lastUpdated = Instant.now(),
                locationMessage = "Forecast ready. Finding nearby viewing spots…"
            )

            val bestNight = nights.filter { it.bestTime != null }.maxByOrNull { it.score }
            val bestInstant = bestNight?.bestTime?.atZone(weather.zoneId)?.toInstant()
            val places = if (bestInstant != null) {
                runCatching {
                    val raw = network.fetchNearbyPlaces(point)
                    val shortlist = raw.sortedByDescending { preliminaryPlaceScore(it) }.take(8)
                    shortlist.map { site ->
                        async {
                            val cloud = runCatching { network.fetchCloudAt(site.point, bestInstant) }.getOrDefault(100)
                            AuroraScoring.rankPlace(site, cloud)
                        }
                    }.awaitAll().sortedByDescending { it.score }
                }.getOrDefault(emptyList())
            } else emptyList()

            _ui.value = _ui.value.copy(
                isLoading = false,
                places = places,
                lastUpdated = Instant.now(),
                locationMessage = if (places.isEmpty()) "Forecast ready. No named dark-site candidates were returned nearby." else "Forecast and nearby spots ready."
            )
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(isLoading = false, error = e.message ?: "Forecast failed", locationMessage = "Forecast could not be completed.")
        }
    }

    private fun preliminaryPlaceScore(p: RawPlace): Double {
        val dark = (p.urbanDistanceKm / 28.0).coerceIn(0.15, 1.0)
        val distancePenalty = (p.distanceKm / 110.0).coerceIn(0.0, 0.38)
        val type = when (p.category) {
            "Viewpoint" -> 1.0
            "Nature reserve" -> 0.96
            "Protected area" -> 0.93
            else -> 0.86
        }
        return 0.7 * dark + 0.3 * type - distancePenalty
    }
}
