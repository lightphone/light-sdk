package com.thelightphone.weather

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


//curl "https://production.lightphonecloud.com/tools/weather/forecast?latitude=37.7749&longitude=-122.4194&current=temperature_2m,apparent_temperature,weather_code&hourly=temperature_2m,apparent_temperature,precipitation,precipitation_probability&daily=temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,precipitation_probability_max,weathercode,windspeed_10m_max,winddirection_10m_dominant,uv_index_max,sunrise,sunset&timezone=auto&forecast_days=7"
private val SAMPLE_OPEN_METEO_RESPONSE = """
{"daily":{"apparent_temperature_max":[24.5,25.1,21.3,24.2,23.7,22.0,26.1],"apparent_temperature_min":[11.9,11.6,13.3,13.4,14.2,13.6,13.1],"precipitation_probability_max":[0,1,0,0,0,0,0],"precipitation_sum":[0.0,0.0,0.0,0.0,0.0,0.0,0.0],"sunrise":["2026-07-28T06:10","2026-07-29T06:11","2026-07-30T06:11","2026-07-31T06:12","2026-08-01T06:13","2026-08-02T06:14","2026-08-03T06:15"],"sunset":["2026-07-28T20:22","2026-07-29T20:21","2026-07-30T20:20","2026-07-31T20:19","2026-08-01T20:18","2026-08-02T20:17","2026-08-03T20:16"],"temperature_2m_max":[23.4,23.9,20.9,23.5,22.8,21.3,25.2],"temperature_2m_min":[12.7,12.5,14.1,14.1,14.4,14.4,13.9],"time":["2026-07-28","2026-07-29","2026-07-30","2026-07-31","2026-08-01","2026-08-02","2026-08-03"],"uv_index_max":[8.15,8.15,8.2,8.15,8.15,7.95,8.0],"weathercode":[1,1,2,0,0,3,0],"winddirection_10m_dominant":[283,275,252,251,246,239,220],"windspeed_10m_max":[29.5,29.7,20.8,19.4,19.9,18.6,18.4]},"hourly":{"time":["2026-07-28T00:00","2026-07-28T01:00","2026-07-28T02:00","2026-07-28T03:00","2026-07-28T04:00","2026-07-28T05:00","2026-07-28T06:00","2026-07-28T07:00","2026-07-28T08:00","2026-07-28T09:00","2026-07-28T10:00","2026-07-28T11:00","2026-07-28T12:00","2026-07-28T13:00","2026-07-28T14:00","2026-07-28T15:00","2026-07-28T16:00","2026-07-28T17:00","2026-07-28T18:00","2026-07-28T19:00","2026-07-28T20:00","2026-07-28T21:00","2026-07-28T22:00","2026-07-28T23:00","2026-07-29T00:00","2026-07-29T01:00","2026-07-29T02:00","2026-07-29T03:00","2026-07-29T04:00","2026-07-29T05:00"],"temperature_2m":[14.0,13.6,13.9,13.3,13.1,13.3,12.7,13.2,15.6,17.4,19.1,21.4,22.8,23.2,23.4,23.1,22.6,21.4,20.1,18.7,17.2,16.2,15.4,14.3,13.8,13.5,13.1,12.9,12.8,12.6],"apparent_temperature":[12.8,12.5,12.9,12.1,12.0,13.1,11.9,13.0,16.3,18.5,20.7,23.4,24.5,24.3,23.9,22.8,21.5,19.6,17.1,15.7,14.3,14.8,15.9,14.7,14.1,13.6,12.9,12.3,12.1,11.8],"precipitation":[0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0],"precipitation_probability":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,0]},"current":{"apparent_temperature":11.9,"interval":900,"temperature_2m":12.7,"time":"2026-07-28T06:00","weather_code":0}}
""".trimIndent()

private val json = Json { ignoreUnknownKeys = true }

private fun sampleForecast(): StoredForecast =
    json.decodeFromString<OpenMeteoForecastResponse>(SAMPLE_OPEN_METEO_RESPONSE).toStoredForecast()

class WeatherModelsTest {

    @Test
    fun `decodes open-meteo shaped dates and seconds-less datetimes`() {
        val forecast = sampleForecast()
        assertEquals(LocalDate.parse("2026-07-28"), forecast.today.date)
        assertEquals(LocalDateTime.parse("2026-07-28T06:10"), forecast.today.sunrise)
        assertEquals(LocalDateTime.parse("2026-07-28T00:00"), forecast.hourly.first().time)
        assertEquals(30, forecast.hourly.size)
    }

    @Test
    fun `dayCount prefers weekly size, then daily size, then falls back to two`() {
        val forecast = sampleForecast() // weekly and daily both have 7 real days

        val weeklyLonger = forecast.copy(weekly = forecast.weekly + forecast.weekly[0])
        assertEquals(8, weeklyLonger.dayCount())

        assertEquals(7, forecast.copy(weekly = emptyList()).dayCount())
        assertEquals(2, forecast.copy(weekly = emptyList(), daily = emptyList()).dayCount())
    }

    @Test
    fun `dayAt reads straight from the daily list when present`() {
        val forecast = sampleForecast()
        assertEquals(forecast.daily[6], forecast.dayAt(6))
        assertNull(forecast.dayAt(7))
    }

    @Test
    fun `dayAt falls back to today, tomorrow, and converted weekly days`() {
        val forecast = sampleForecast().copy(daily = emptyList())

        assertEquals(forecast.today, forecast.dayAt(0))
        assertEquals(forecast.tomorrow, forecast.dayAt(1))

        val thirdDay = forecast.dayAt(2)
        assertNotNull(thirdDay)
        assertEquals(LocalDate.parse("2026-07-30"), thirdDay.date)
        assertEquals(2, thirdDay.weatherCode)
        // WeeklyDay carries no wind/UV/sunrise data; the fallback conversion
        // has to make those up rather than lying with a fake value.
        assertNull(thirdDay.sunrise)
        assertNull(thirdDay.sunset)
        assertEquals(0, thirdDay.windDirectionDominant)
    }

    @Test
    fun `dayAt returns null out of range`() {
        val forecast = sampleForecast().copy(daily = emptyList(), weekly = emptyList())
        assertNull(forecast.dayAt(2))
        assertNull(forecast.dayAt(-1))
    }

    @Test
    fun `hoursForToday keeps only todays hours at or after the current hour`() {
        val forecast = sampleForecast()
        val hours = forecast.hoursForToday(now = LocalDateTime.parse("2026-07-28T10:30"))

        assertEquals(14, hours.size) // 10:00 through 23:00
        assertEquals(LocalDateTime.parse("2026-07-28T10:00"), hours.first().time)
        assertEquals(LocalDateTime.parse("2026-07-28T23:00"), hours.last().time)
        assertTrue(hours.all { it.time.date == LocalDate.parse("2026-07-28") })
    }

    @Test
    fun `hoursForToday truncates now to the top of the hour`() {
        val forecast = sampleForecast()
        val hours = forecast.hoursForToday(now = LocalDateTime.parse("2026-07-28T12:45"))

        assertEquals(12, hours.size) // 12:00 through 23:00
        assertEquals(LocalDateTime.parse("2026-07-28T12:00"), hours.first().time)
    }

    @Test
    fun `weather descriptions come from the wmo code table`() {
        val forecast = sampleForecast()
        assertEquals("Clear sky", forecast.current?.weatherDescription)
        assertEquals("Mainly clear, partly cloudy, or overcast", forecast.today.weatherDescription)
        assertEquals("Thunderstorm with hail", wmoWeatherDescription(96))
        assertEquals("Weather code 42", wmoWeatherDescription(42))
    }

    @Test
    fun `wind compass reads direction off the day forecast`() {
        val forecast = sampleForecast()
        assertEquals("W", forecast.today.windCompass)
        assertEquals("N", degreesToCompass(0))
        assertEquals("N", degreesToCompass(359))
    }
}
