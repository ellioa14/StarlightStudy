package com.example.aurorascout

import java.time.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

object AuroraScoring {
    fun buildCalendar(
        latitude: Double,
        weather: WeatherForecast,
        kpForecast: List<KpPoint>,
        outlook: List<OutlookDay>,
        now: Instant = Instant.now()
    ): List<ForecastNight> {
        val outlookMap = outlook.associateBy { it.dateUtc }
        return weather.solarDays.keys.sorted().take(7).map { date ->
            val solar = weather.solarDays[date]!!
            val candidates = weather.hours.filter { h ->
                h.time.toLocalDate() == date &&
                    h.time.atZone(weather.zoneId).toInstant().isAfter(now.minusSeconds(1800)) &&
                    darknessFactor(h.time, solar) > 0.0
            }
            val scored = candidates.map { hour ->
                val instant = hour.time.atZone(weather.zoneId).toInstant()
                val direct = kpForecast.filter { !it.time.isAfter(instant) }.maxByOrNull { it.time }
                    ?.takeIf { Duration.between(it.time, instant).abs() <= Duration.ofHours(4) }
                val kp = direct?.kp ?: outlookMap[instant.atZone(ZoneOffset.UTC).toLocalDate()]?.maxKp ?: 2.0
                val aurora = auroraFactor(latitude, kp)
                val clear = (1.0 - hour.cloudCover.coerceIn(0, 100) / 100.0).pow(1.25)
                val dark = darknessFactor(hour.time, solar)
                val score = (100.0 * aurora * (0.22 + 0.78 * clear) * dark).roundToInt().coerceIn(0, 100)
                Triple(hour, kp, Pair(score, direct != null))
            }
            val best = scored.maxByOrNull { it.third.first }
            ForecastNight(
                date = date,
                score = best?.third?.first ?: 0,
                bestTime = best?.first?.time,
                kp = best?.second ?: outlookMap[date]?.maxKp ?: 0.0,
                cloudCover = best?.first?.cloudCover ?: 100,
                confidence = if (best?.third?.second == true) "Higher" else "Outlook",
                source = if (best?.third?.second == true) "NOAA 3-day Kp" else "NOAA 27-day outlook"
            )
        }
    }

    fun rankPlace(raw: RawPlace, cloudCover: Int): ViewingPlace {
        val darkness = (raw.urbanDistanceKm / 28.0).coerceIn(0.15, 1.0)
        val clear = 1.0 - cloudCover.coerceIn(0, 100) / 100.0
        val type = when (raw.category) {
            "Viewpoint" -> 1.0
            "Nature reserve" -> 0.96
            "Protected area" -> 0.93
            else -> 0.86
        }
        val distancePenalty = (raw.distanceKm / 110.0).coerceIn(0.0, 0.38)
        val score = ((0.52 * darkness + 0.33 * clear + 0.15 * type - distancePenalty) * 100)
            .roundToInt().coerceIn(0, 100)
        return ViewingPlace(
            raw.name, raw.point, raw.category, raw.distanceKm, cloudCover,
            (darkness * 100).roundToInt(), score
        )
    }

    private fun darknessFactor(t: LocalDateTime, solar: SolarDay): Double {
        val sunrise = solar.sunrise
        val sunset = solar.sunset
        val isNight = t.isBefore(sunrise) || t.isAfter(sunset)
        if (!isNight) return 0.0
        val deepMorning = sunrise.minusMinutes(90)
        val deepEvening = sunset.plusMinutes(90)
        return if (t.isBefore(deepMorning) || t.isAfter(deepEvening)) 1.0 else 0.62
    }

    private fun auroraFactor(latitude: Double, kp: Double): Double {
        val lat = abs(latitude)
        val required = when {
            lat >= 67 -> 1.0
            lat >= 63 -> 2.0
            lat >= 60 -> 3.0
            lat >= 56 -> 4.0
            lat >= 52 -> 5.0
            lat >= 48 -> 6.0
            lat >= 44 -> 7.0
            lat >= 40 -> 8.0
            else -> 9.0
        }
        return ((kp - required + 1.45) / 2.9).coerceIn(0.02, 1.0)
    }
}
