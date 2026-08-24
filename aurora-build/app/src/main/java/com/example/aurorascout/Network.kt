package com.example.aurorascout

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

class AuroraNetwork {
    suspend fun fetchWeather(point: GeoPoint): WeatherForecast = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast?")
            append("latitude=${point.latitude}&longitude=${point.longitude}")
            append("&hourly=cloud_cover,visibility")
            append("&daily=sunrise,sunset")
            append("&timezone=auto&forecast_days=7")
        }
        parseWeather(get(url))
    }

    suspend fun fetchKpForecast(): List<KpPoint> = withContext(Dispatchers.IO) {
        val root = JSONArray(get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"))
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
        buildList {
            for (i in 1 until root.length()) {
                val row = root.getJSONArray(i)
                val local = LocalDateTime.parse(row.getString(0).substringBefore('.'), fmt)
                add(KpPoint(local.toInstant(ZoneOffset.UTC), row.optString(1, "0").toDoubleOrNull() ?: 0.0, row.optString(2, "predicted"), row.optString(3).ifBlank { null }))
            }
        }
    }

    suspend fun fetch27DayOutlook(): List<OutlookDay> = withContext(Dispatchers.IO) {
        val text = get("https://services.swpc.noaa.gov/text/27-day-outlook.txt")
        val regex = Regex("""^(\d{4})\s+([A-Za-z]{3})\s+(\d{1,2})\s+\d+\s+\d+\s+(\d+)\s*$""")
        text.lineSequence().mapNotNull { line ->
            val m = regex.matchEntire(line.trim()) ?: return@mapNotNull null
            val month = when (m.groupValues[2].lowercase(Locale.US)) {
                "jan" -> Month.JANUARY; "feb" -> Month.FEBRUARY; "mar" -> Month.MARCH
                "apr" -> Month.APRIL; "may" -> Month.MAY; "jun" -> Month.JUNE
                "jul" -> Month.JULY; "aug" -> Month.AUGUST; "sep" -> Month.SEPTEMBER
                "oct" -> Month.OCTOBER; "nov" -> Month.NOVEMBER; else -> Month.DECEMBER
            }
            OutlookDay(LocalDate.of(m.groupValues[1].toInt(), month, m.groupValues[3].toInt()), m.groupValues[4].toDouble())
        }.toList()
    }

    suspend fun fetchLiveAurora(point: GeoPoint): LiveAurora = withContext(Dispatchers.IO) {
        val json = JSONObject(get("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json"))
        val coords = json.getJSONArray("coordinates")
        val targetLon = ((point.longitude % 360.0) + 360.0) % 360.0
        var bestDist = Double.MAX_VALUE
        var probability = 0
        for (i in 0 until coords.length()) {
            val row = coords.getJSONArray(i)
            val lon = row.getDouble(0); val lat = row.getDouble(1)
            val dxRaw = abs(lon - targetLon); val dx = min(dxRaw, 360.0 - dxRaw); val dy = abs(lat - point.latitude)
            val d = dx * dx + dy * dy
            if (d < bestDist) { bestDist = d; probability = row.getInt(2) }
        }
        LiveAurora(probability, json.optString("Forecast Time").toInstantOrNull(), json.optString("Observation Time").toInstantOrNull())
    }

    suspend fun fetchNearbyPlaces(origin: GeoPoint, radiusMeters: Int = 70000): List<RawPlace> = withContext(Dispatchers.IO) {
        val q = """
            [out:json][timeout:20];
            (
              nwr(around:$radiusMeters,${origin.latitude},${origin.longitude})["tourism"="viewpoint"];
              nwr(around:$radiusMeters,${origin.latitude},${origin.longitude})["leisure"="nature_reserve"];
              nwr(around:$radiusMeters,${origin.latitude},${origin.longitude})["boundary"="protected_area"];
              nwr(around:$radiusMeters,${origin.latitude},${origin.longitude})["tourism"="camp_site"];
              node(around:90000,${origin.latitude},${origin.longitude})["place"~"city|town"];
            );
            out center tags;
        """.trimIndent()
        val body = "data=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
        val elements = JSONObject(post("https://overpass-api.de/api/interpreter", body)).getJSONArray("elements")
        val urban = mutableListOf<GeoPoint>(); val sites = mutableListOf<Triple<String, GeoPoint, String>>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i); val tags = e.optJSONObject("tags") ?: continue; val p = elementPoint(e) ?: continue
            val place = tags.optString("place")
            if (place == "city" || place == "town") { urban += p; continue }
            val name = tags.optString("name").trim(); if (name.isBlank()) continue
            val category = when {
                tags.optString("tourism") == "viewpoint" -> "Viewpoint"
                tags.optString("leisure") == "nature_reserve" -> "Nature reserve"
                tags.optString("tourism") == "camp_site" -> "Campground"
                tags.optString("boundary") == "protected_area" -> "Protected area"
                else -> continue
            }
            sites += Triple(name, p, category)
        }
        sites.distinctBy { it.first.lowercase(Locale.US) }.map { (name, point, category) ->
            RawPlace(name, point, category, haversineKm(origin, point), urban.minOfOrNull { haversineKm(point, it) } ?: 20.0)
        }.sortedBy { it.distanceKm }.take(40)
    }

    suspend fun fetchCloudAt(point: GeoPoint, instant: Instant): Int = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}&hourly=cloud_cover&timezone=UTC&forecast_days=7"
        val json = JSONObject(get(url)).getJSONObject("hourly"); val times = json.getJSONArray("time"); val clouds = json.getJSONArray("cloud_cover")
        val targetEpoch = instant.epochSecond; var bestIndex = 0; var bestDelta = Long.MAX_VALUE
        for (i in 0 until times.length()) {
            val t = LocalDateTime.parse(times.getString(i)).toInstant(ZoneOffset.UTC).epochSecond; val d = abs(t - targetEpoch)
            if (d < bestDelta) { bestDelta = d; bestIndex = i }
        }
        clouds.optInt(bestIndex, 100)
    }

    private fun parseWeather(text: String): WeatherForecast {
        val root = JSONObject(text); val zone = ZoneId.of(root.getString("timezone")); val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time"); val clouds = hourly.getJSONArray("cloud_cover"); val visibility = hourly.optJSONArray("visibility")
        val hours = buildList { for (i in 0 until times.length()) add(WeatherHour(LocalDateTime.parse(times.getString(i)), clouds.optInt(i, 100), visibility?.let { if (it.isNull(i)) null else it.optInt(i) })) }
        val daily = root.getJSONObject("daily"); val dates = daily.getJSONArray("time"); val sunrises = daily.getJSONArray("sunrise"); val sunsets = daily.getJSONArray("sunset")
        val solar = buildMap { for (i in 0 until dates.length()) { val date = LocalDate.parse(dates.getString(i)); put(date, SolarDay(date, LocalDateTime.parse(sunrises.getString(i)), LocalDateTime.parse(sunsets.getString(i)))) } }
        return WeatherForecast(zone, hours, solar)
    }

    private fun elementPoint(e: JSONObject): GeoPoint? = if (e.has("lat") && e.has("lon")) GeoPoint(e.getDouble("lat"), e.getDouble("lon")) else e.optJSONObject("center")?.let { GeoPoint(it.getDouble("lat"), it.getDouble("lon")) }

    private fun get(url: String): String { val c = URL(url).openConnection() as HttpURLConnection; c.connectTimeout = 12000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "AuroraScout/1.0 Android"); return c.useText() }
    private fun post(url: String, body: String): String { val c = URL(url).openConnection() as HttpURLConnection; c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 12000; c.readTimeout = 25000; c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("User-Agent", "AuroraScout/1.0 Android"); c.outputStream.bufferedWriter().use { it.write(body) }; return c.useText() }
    private fun HttpURLConnection.useText(): String = try { val code = responseCode; val stream = if (code in 200..299) inputStream else errorStream; val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(); if (code !in 200..299) error("HTTP $code: ${body.take(180)}"); body } finally { disconnect() }
    private fun String.toInstantOrNull(): Instant? = try { Instant.parse(this) } catch (_: Exception) { null }

    companion object {
        fun haversineKm(a: GeoPoint, b: GeoPoint): Double { val r = 6371.0; val dLat = Math.toRadians(b.latitude - a.latitude); val dLon = Math.toRadians(b.longitude - a.longitude); val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2); return 2 * r * asin(sqrt(x)) }
    }
}
