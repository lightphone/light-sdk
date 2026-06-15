package com.thelightphone.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlin.math.roundToInt

@InitialScreen
class WeatherHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, WeatherViewModel>(sealedActivity) {

    override val viewModelClass: Class<WeatherViewModel>
        get() = WeatherViewModel::class.java

    override val showBackBar: Boolean = false

    override fun createViewModel(): WeatherViewModel = WeatherViewModel(dataStore)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.uiState.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()
        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = state.mode) {
                    is WeatherScreenMode.LocationInput -> {
                        LightTextInputEditor(
                            title = "Location",
                            editorKey = state.locationInputSession,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            state = textFieldState,
                            onSubmit = viewModel::submitLocation,
                            onBack = viewModel::cancelLocationInput,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is WeatherScreenMode.Loading -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LightTopBar(
                                center = LightTopBarCenter.Text("Weather"),
                                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                            )
                            LightText(
                                text = "Loading…",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
                            )
                        }
                    }

                    is WeatherScreenMode.Settings -> {
                        SettingsContent(
                            temperatureUnit = state.temperatureUnit,
                            onBack = viewModel::closeSettings,
                            onChangeLocation = viewModel::openLocationFromSettings,
                            onToggleUnit = viewModel::toggleTemperatureUnit,
                        )
                    }

                    is WeatherScreenMode.Weekly -> {
                        WeeklyForecastContent(
                            locationName = mode.locationName,
                            days = mode.days,
                            temperatureUnit = state.temperatureUnit,
                            onBack = viewModel::closeWeekly,
                            onOpenSettings = viewModel::openSettings,
                        )
                    }

                    is WeatherScreenMode.Weather -> {
                        WeatherContent(
                            locationName = mode.locationName,
                            day = when (mode.selectedDay) {
                                WeatherDay.Today -> mode.forecast.today
                                WeatherDay.Tomorrow -> mode.forecast.tomorrow
                            },
                            dayLabel = when (mode.selectedDay) {
                                WeatherDay.Today -> "Today"
                                WeatherDay.Tomorrow -> "Tomorrow"
                            },
                            selectedDay = mode.selectedDay,
                            temperatureUnit = state.temperatureUnit,
                            onToggleDay = viewModel::toggleDay,
                            onOpenWeekly = viewModel::openWeekly,
                            onOpenSettings = viewModel::openSettings,
                        )
                    }
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(
                        message = message,
                        onClose = viewModel::dismissError,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherContent(
    locationName: String,
    day: DayForecast,
    dayLabel: String,
    selectedDay: WeatherDay,
    temperatureUnit: TemperatureUnit,
    onToggleDay: () -> Unit,
    onOpenWeekly: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text(locationName),
            rightButton = settingsButton(onOpenSettings),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            WeatherLine("$dayLabel — ${day.date}")
            WeatherLine(day.weatherDescription)
            WeatherLine("High ${formatTemperature(day.tempMaxC, temperatureUnit)}")
            WeatherLine("Low ${formatTemperature(day.tempMinC, temperatureUnit)}")
            WeatherLine(
                "Feels like ${formatTemperature(day.apparentTempMaxC, temperatureUnit)}" +
                    " / ${formatTemperature(day.apparentTempMinC, temperatureUnit)}",
            )
            WeatherLine("Rain ${formatRain(day.precipitationMm, temperatureUnit)}")
            day.precipitationProbabilityMax?.let { probability ->
                WeatherLine("Precip chance $probability%")
            }
            WeatherLine("Wind ${day.windSpeedMaxKmh.round1()} km/h ${day.windCompass}")
            WeatherLine("UV index ${day.uvIndexMax.round1()}")
            WeatherLine("Sunrise ${formatTime(day.sunrise)}")
            WeatherLine("Sunset ${formatTime(day.sunset)}")
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(
                    text = when (selectedDay) {
                        WeatherDay.Today -> "TOMORROW"
                        WeatherDay.Tomorrow -> "TODAY"
                    },
                    onClick = onToggleDay,
                ),
                LightBarButton.Text(
                    text = "WEEK",
                    onClick = onOpenWeekly,
                ),
            ),
        )
    }
}

@Composable
private fun WeeklyForecastContent(
    locationName: String,
    days: List<WeeklyDay>,
    temperatureUnit: TemperatureUnit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Week — $locationName"),
            rightButton = settingsButton(onOpenSettings),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            days.forEach { day ->
                Column(
                    modifier = Modifier.padding(bottom = 1.5f.gridUnitsAsDp()),
                ) {
                    WeatherLine(formatWeeklyDayLabel(day.date))
                    WeatherLine(
                        "High ${formatTemperature(day.tempMaxC, temperatureUnit)}" +
                            " · Low ${formatTemperature(day.tempMinC, temperatureUnit)}",
                    )
                    WeatherLine("Rain ${formatRain(day.precipitationMm, temperatureUnit)}")
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    temperatureUnit: TemperatureUnit,
    onBack: () -> Unit,
    onChangeLocation: () -> Unit,
    onToggleUnit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Settings"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightText(
                text = "LOCATION",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .clickable(onClick = onChangeLocation)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
            LightText(
                text = when (temperatureUnit) {
                    TemperatureUnit.Celsius -> "UNITS: CELSIUS"
                    TemperatureUnit.Fahrenheit -> "UNITS: FAHRENHEIT"
                },
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .clickable(onClick = onToggleUnit)
                    .padding(vertical = 0.75f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun settingsButton(onClick: () -> Unit) = LightBarButton.LightIcon(
    icon = LightIcons.SETTINGS,
    onClick = onClick,
    contentDescription = "Settings",
)

@Composable
private fun WeatherLine(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Copy,
        modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp()),
    )
}

private fun Double.round1(): String {
    val rounded = (this * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

private fun formatTime(iso: String): String {
    val timePart = iso.substringAfter('T', iso)
    return timePart.take(5)
}
