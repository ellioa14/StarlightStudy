package com.example.aurorascout

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class GeoPoint(val latitude: Double, val longitude: Double)

data class WeatherHour(
    val time: LocalDateTime,
    val cloudCover: Int,
    val visibilityMeters: Int?
)

data class SolarDay(
    val date: LocalDate,
    val sunrise: LocalDateTime,
    val sunset: LocalDateTime
)

data class WeatherForecast(
    val zoneId: ZoneId,
    val hours: List<WeatherHour>,
    val solarDays: Map<LocalDate, SolarDay>
)

data class KpPoint(
    val time: Instant,
    val kp: Double,
    val kind: String,
    val noaaScale: String?
)

data class OutlookDay(val dateUtc: LocalDate, val maxKp: Double)

data class LiveAurora(
    val probabilityPercent: Int,
    val forecastTime: Instant?,
    val observationTime: Instant?
)

data class ForecastNight(
    val date: LocalDate,
    val score: Int,
    val bestTime: LocalDateTime?,
    val kp: Double,
    val cloudCover: Int,
    val confidence: String,
    val source: String
)

data class RawPlace(
    val name: String,
    val point: GeoPoint,
    val category: String,
    val distanceKm: Double,
    val urbanDistanceKm: Double
)

data class ViewingPlace(
    val name: String,
    val point: GeoPoint,
    val category: String,
    val distanceKm: Double,
    val cloudCover: Int,
    val darknessScore: Int,
    val score: Int
)

data class AuroraUiState(
    val isLoading: Boolean = false,
    val location: GeoPoint? = null,
    val zoneId: ZoneId? = null,
    val liveAurora: LiveAurora? = null,
    val nights: List<ForecastNight> = emptyList(),
    val places: List<ViewingPlace> = emptyList(),
    val lastUpdated: Instant? = null,
    val error: String? = null,
    val locationMessage: String = "Location is needed to build your aurora forecast."
)
