package com.thelightphone.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.text.Charsets.UTF_8

private const val WEATHER_API_BASE = "https://production.lightphonecloud.com"

@Serializable
internal data class GeocodingResponse(
    val results: List<GeocodingResult> = emptyList(),
)

@Serializable
data class GeocodingResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
)

@Serializable
internal data class OpenMeteoForecastResponse(
    val daily: OpenMeteoDaily? = null,
    val hourly: OpenMeteoHourly? = null,
    val current: OpenMeteoCurrent? = null,
)

@Serializable
internal data class OpenMeteoHourly(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature2m: List<Double> = emptyList(),
    @SerialName("apparent_temperature") val apparentTemperature: List<Double> = emptyList(),
    val precipitation: List<Double> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
)

@Serializable
internal data class OpenMeteoCurrent(
    @SerialName("temperature_2m") val temperature2m: Double,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("weather_code") val weatherCode: Int,
)

@Serializable
internal data class OpenMeteoDaily(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double> = emptyList(),
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double> = emptyList(),
    @SerialName("apparent_temperature_max") val apparentTemperatureMax: List<Double> = emptyList(),
    @SerialName("apparent_temperature_min") val apparentTemperatureMin: List<Double> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?> = emptyList(),
    val weathercode: List<Int> = emptyList(),
    @SerialName("windspeed_10m_max") val windspeed10mMax: List<Double> = emptyList(),
    @SerialName("winddirection_10m_dominant") val winddirection10mDominant: List<Int> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double> = emptyList(),
    val sunrise: List<String> = emptyList(),
    val sunset: List<String> = emptyList(),
)

internal class WeatherApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    suspend fun searchLocations(query: String): Result<List<GeocodingResult>> = runCatching {
        val encoded = URLEncoder.encode(query.trim(), UTF_8.name())
        val response = client.get(
            "$WEATHER_API_BASE/tools/weather/geolocation?name=$encoded&count=15",
        )

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(500)
            throw IllegalStateException("Geocoding HTTP ${response.status.value}: $body")
        }

        val geo: GeocodingResponse = response.body()
        geo.results.ifEmpty { throw LocationNotFoundException() }
    }

    suspend fun fetchForecast(latitude: Double, longitude: Double): Result<StoredForecast> = runCatching {
        val response = client.get(
            "$WEATHER_API_BASE/tools/weather/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,apparent_temperature,weather_code" +
                "&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                ",apparent_temperature_max,apparent_temperature_min" +
                ",precipitation_sum,precipitation_probability_max" +
                ",weathercode,windspeed_10m_max,winddirection_10m_dominant" +
                ",uv_index_max,sunrise,sunset" +
                "&timezone=auto&forecast_days=7",
        )

        if (!response.status.isSuccess()) {
            val body = response.bodyAsText().take(500)
            throw IllegalStateException("Forecast HTTP ${response.status.value}: $body")
        }

        val forecastResponse: OpenMeteoForecastResponse = response.body()
        val daily = forecastResponse.daily
            ?: throw IllegalStateException("No forecast data available.")

        if (daily.time.size < 2) {
            throw IllegalStateException("Forecast did not include today and tomorrow.")
        }

        val current = forecastResponse.current?.let {
            CurrentConditions(
                tempC = it.temperature2m,
                apparentTempC = it.apparentTemperature,
                weatherCode = it.weatherCode,
            )
        }
        val today = daily.toDayForecast(index = 0)
        val tomorrow = daily.toDayForecast(index = 1)
        val dailyForecasts = daily.time.indices.map { index -> daily.toDayForecast(index) }
        val weekly = daily.time.indices.map { index ->
            WeeklyDay(
                date = daily.time[index],
                tempMaxC = daily.temperature2mMax[index],
                tempMinC = daily.temperature2mMin[index],
                precipitationMm = daily.precipitationSum[index],
                precipitationProbabilityMax = daily.precipitationProbabilityMax.getOrNull(index),
                weatherCode = daily.weathercode[index],
            )
        }
        val hourly = forecastResponse.hourly?.toHourlyForecasts().orEmpty()
        StoredForecast(
            today = today,
            tomorrow = tomorrow,
            weekly = weekly,
            hourly = hourly,
            current = current,
            daily = dailyForecasts,
        )
    }

    fun close() {
        client.close()
    }
}

internal class LocationNotFoundException : Exception("Location not found.")

private fun OpenMeteoDaily.toDayForecast(index: Int): DayForecast {
    return DayForecast(
        date = time[index],
        tempMaxC = temperature2mMax[index],
        tempMinC = temperature2mMin[index],
        apparentTempMaxC = apparentTemperatureMax[index],
        apparentTempMinC = apparentTemperatureMin[index],
        precipitationMm = precipitationSum[index],
        precipitationProbabilityMax = precipitationProbabilityMax.getOrNull(index),
        weatherCode = weathercode[index],
        windSpeedMaxKmh = windspeed10mMax[index],
        windDirectionDominant = winddirection10mDominant[index],
        uvIndexMax = uvIndexMax[index],
        sunrise = sunrise[index],
        sunset = sunset[index],
    )
}

private fun OpenMeteoHourly.toHourlyForecasts(): List<HourlyForecast> {
    return time.indices.map { index ->
        HourlyForecast(
            time = time[index],
            tempC = temperature2m[index],
            apparentTempC = apparentTemperature[index],
            precipitationMm = precipitation[index],
            precipitationProbability = precipitationProbability.getOrNull(index),
        )
    }
}

fun GeocodingResult.displayName(): String {
    val region = regionLabel()
    return if (region.isEmpty()) name else "$name, $region"
}

fun GeocodingResult.regionLabel(): String =
    listOfNotNull(admin1, country).joinToString(", ")
