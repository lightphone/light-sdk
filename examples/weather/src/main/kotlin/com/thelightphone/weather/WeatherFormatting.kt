package com.thelightphone.weather

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlin.math.roundToInt

enum class TemperatureUnit {
    Celsius,
    Fahrenheit,
}

fun TemperatureUnit.toggle(): TemperatureUnit = when (this) {
    TemperatureUnit.Celsius -> TemperatureUnit.Fahrenheit
    TemperatureUnit.Fahrenheit -> TemperatureUnit.Celsius
}

fun TemperatureUnit.storageValue(): String = when (this) {
    TemperatureUnit.Celsius -> "C"
    TemperatureUnit.Fahrenheit -> "F"
}

fun temperatureUnitFromStorage(value: String?): TemperatureUnit = when (value) {
    "F" -> TemperatureUnit.Fahrenheit
    else -> TemperatureUnit.Celsius
}

fun TemperatureUnit.displayLabel(): String = when (this) {
    TemperatureUnit.Celsius -> "Metric"
    TemperatureUnit.Fahrenheit -> "Imperial"
}

fun shortLocationName(fullName: String): String = fullName.substringBefore(',').trim()

fun displayTemperatureC(day: DayForecast, current: CurrentConditions?, unit: TemperatureUnit): String {
    val celsius = current?.tempC ?: day.tempMaxC
    return formatTemperature(celsius, unit)
}

fun displayWeatherDescription(day: DayForecast, current: CurrentConditions?): String {
    return current?.weatherDescription ?: day.weatherDescription
}

fun formatTemperature(
    celsius: Double,
    unit: TemperatureUnit,
    displayUnit: Boolean = false,
): String {
    val value = when (unit) {
        TemperatureUnit.Celsius -> celsius
        TemperatureUnit.Fahrenheit -> celsius * 9.0 / 5.0 + 32.0
    }
    val suffix = when (displayUnit) {
        false -> "°"
        true -> when (unit) {
            TemperatureUnit.Celsius -> "°C"
            TemperatureUnit.Fahrenheit -> "°F"
        }
    }
    val formatted = if (displayUnit) value.round1() else value.roundToInt().toString()
    return "$formatted$suffix"
}

fun formatHighLowLine(day: DayForecast, unit: TemperatureUnit): String {
    val high = formatTemperature(day.tempMaxC, unit)
    val low = formatTemperature(day.tempMinC, unit)
    val feelsHigh = formatTemperature(day.apparentTempMaxC, unit)
    val feelsLow = formatTemperature(day.apparentTempMinC, unit)
    return "$high / $low (feels like $feelsHigh / $feelsLow)"
}

fun formatPrecipitationDetail(day: DayForecast, unit: TemperatureUnit): String {
    val amount = formatRain(day.precipitationMm, unit)
    val probability = day.precipitationProbabilityMax
    return if (probability != null) "$amount ($probability%)" else amount
}

fun formatWindSpeed(kmh: Double, compass: String, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.Fahrenheit -> "${(kmh * 0.621371).roundToInt()} mph $compass"
    TemperatureUnit.Celsius -> "${kmh.roundToInt()} km/h $compass"
}

fun formatTimeAmPm(dateTime: LocalDateTime?): String {
    dateTime ?: return "--:--"
    val (hour, period) = dateTime.to12Hour()
    return "$hour:${dateTime.minute.toString().padStart(2, '0')} $period"
}

fun formatUvIndex(value: Double): String = value.round1()

fun formatRain(mm: Double, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.Celsius -> "${mm.round1()} mm"
    TemperatureUnit.Fahrenheit -> "${(mm / 25.4).round1()} in"
}

fun formatWeeklyHighLowLine(day: WeeklyDay, unit: TemperatureUnit): String {
    val high = formatTemperature(day.tempMaxC, unit)
    val low = formatTemperature(day.tempMinC, unit)
    return "$high / $low"
}

fun formatWeeklyPrecipitationDetail(day: WeeklyDay, unit: TemperatureUnit): String {
    val amount = formatRain(day.precipitationMm, unit)
    val probability = day.precipitationProbabilityMax
    return if (probability != null) "$amount ($probability%)" else amount
}

fun formatHourLabel(dateTime: LocalDateTime): String {
    val (hour, period) = dateTime.to12Hour()
    return "$hour$period"
}

fun formatHourlyTempLine(hour: HourlyForecast, unit: TemperatureUnit): String {
    val temp = formatTemperature(hour.tempC, unit)
    val feels = formatTemperature(hour.apparentTempC, unit)
    return "$temp (feels like $feels)"
}

fun formatHourlyRainLine(hour: HourlyForecast, unit: TemperatureUnit): String {
    val rain = formatRain(hour.precipitationMm, unit)
    val probability = hour.precipitationProbability
    return if (probability != null) "Rain: $rain ($probability%)" else "Rain: $rain"
}

fun formatDailyTitle(date: LocalDate): String {
    val weekday = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tues"
        DayOfWeek.WEDNESDAY -> "Weds"
        DayOfWeek.THURSDAY -> "Thurs"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }
    return "$weekday ${date.month.displayName()} ${date.dayOfMonth}"
}

fun formatWeeklyDayLabel(date: LocalDate): String {
    val dayOfWeek = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$dayOfWeek ${date.month.displayName()} ${date.dayOfMonth}"
}

private fun Month.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

private fun LocalDateTime.to12Hour(): Pair<Int, String> {
    val period = if (hour < 12) "AM" else "PM"
    val twelveHour = hour % 12
    return (if (twelveHour == 0) 12 else twelveHour) to period
}

private fun Double.round1(): String {
    val rounded = (this * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
