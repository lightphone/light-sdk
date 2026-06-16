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
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp

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
                            title = "Search Location",
                            editorKey = state.locationInputSession,
                            keyboardOptionsFlow = keyboardOptionsFlow,
                            state = textFieldState,
                            onSubmit = viewModel::submitLocation,
                            onBack = viewModel::cancelLocationInput,
                            submitIcon = LightIcons.SEARCH,
                            showBackButton = state.canCancelLocationInput,
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
                            locationName = mode.locationName,
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
                            onGoToToday = viewModel::goToToday,
                            onOpenSettings = viewModel::openSettings,
                        )
                    }

                    is WeatherScreenMode.Hourly -> {
                        HourlyForecastContent(
                            date = mode.forecast.today.date,
                            hours = mode.forecast.hoursForToday(),
                            temperatureUnit = state.temperatureUnit,
                            onClose = viewModel::closeHourly,
                            onOpenWeekly = viewModel::openWeekly,
                            onOpenSettings = viewModel::openSettings,
                        )
                    }

                    is WeatherScreenMode.Weather -> {
                        val day = mode.forecast.today
                        WeatherContent(
                            day = day,
                            currentConditions = mode.forecast.current,
                            temperatureUnit = state.temperatureUnit,
                            onOpenWeekly = viewModel::openWeekly,
                            onOpenHourly = viewModel::openHourly,
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
    day: DayForecast,
    currentConditions: CurrentConditions?,
    temperatureUnit: TemperatureUnit,
    onOpenWeekly: () -> Unit,
    onOpenHourly: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sectionPadding = Modifier.padding(bottom = 1f.gridUnitsAsDp())

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text(formatDailyTitle(day.date)),
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            HeroTemperatureText(
                text = displayTemperatureC(day, currentConditions, temperatureUnit),
            )

            Column(modifier = sectionPadding) {
                WeatherBodyText(displayWeatherDescription(day, currentConditions))
                WeatherBodyText(formatHighLowLine(day, temperatureUnit))
            }

            Column {
                WeatherDetailLine(
                    label = "Precipitation",
                    value = formatPrecipitationDetail(day, temperatureUnit),
                )
                WeatherDetailLine(
                    label = "Wind",
                    value = formatWindSpeed(day.windSpeedMaxKmh, day.windCompass, temperatureUnit),
                )
                WeatherDetailLine(
                    label = "UV Index",
                    value = formatUvIndex(day.uvIndexMax),
                )
                WeatherDetailLine(
                    label = "Sunrise",
                    value = formatTimeAmPm(day.sunrise),
                )
                WeatherDetailLine(
                    label = "Sunset",
                    value = formatTimeAmPm(day.sunset),
                )
            }
        }

        LightBottomBar(
            items = listOf(
                settingsButton(onOpenSettings),
                LightBarButton.Text(
                    text = "THIS WEEK",
                    onClick = onOpenWeekly,
                ),
                menuButton(onClick = onOpenHourly),
            ),
        )
    }
}

@Composable
private fun WeatherDetailLine(label: String, value: String) {
    val colors = LightThemeTokens.colors
    val style = compactCopyStyle()

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append("$label: ")
            }
            append(value)
        },
        style = style,
        color = colors.content,
    )
}

@Composable
private fun compactCopyStyle(): TextStyle {
    val base = LightThemeTokens.typography.copy
    return base.copy(
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = (base.fontSize.value * 1.2f).designVerticalPxToSp(),
    )
}

@Composable
private fun HeroTemperatureText(text: String) {
    val colors = LightThemeTokens.colors
    val base = LightThemeTokens.typography.title
    val scale = 0.8f
    val style = base.copy(
        fontSize = (base.fontSize.value * scale).designVerticalPxToSp(),
        lineHeight = (base.fontSize.value * scale * 1.05f).designVerticalPxToSp(),
    )
    Text(text = text, style = style, color = colors.content)
}

@Composable
private fun WeatherBodyText(text: String) {
    Text(
        text = text,
        style = compactCopyStyle(),
        color = LightThemeTokens.colors.content,
    )
}

@Composable
private fun WeatherBoldLine(text: String) {
    Text(
        text = text,
        style = compactCopyStyle().copy(fontWeight = FontWeight.Bold),
        color = LightThemeTokens.colors.content,
    )
}

@Composable
private fun HourlyForecastContent(
    date: String,
    hours: List<HourlyForecast>,
    temperatureUnit: TemperatureUnit,
    onClose: () -> Unit,
    onOpenWeekly: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text(formatDailyTitle(date)),
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            hours.forEach { hour ->
                Column(
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                ) {
                    WeatherBoldLine(formatHourLabel(hour.time))
                    WeatherLine(formatHourlyTempLine(hour, temperatureUnit))
                    WeatherLine(formatHourlyRainLine(hour, temperatureUnit))
                }
            }
        }

        LightBottomBar(
            items = listOf(
                settingsButton(onOpenSettings),
                LightBarButton.Text(
                    text = "THIS WEEK",
                    onClick = onOpenWeekly,
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.CLOSE,
                    onClick = onClose,
                    contentDescription = "Close",
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
    onGoToToday: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            center = LightTopBarCenter.Text(shortLocationName(locationName)),
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )

        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 1f.gridUnitsAsDp()),
        ) {
            days.forEach { day ->
                Column(
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                ) {
                    WeatherBoldLine(formatDailyTitle(day.date))
                    WeatherLine(formatWeeklySummaryLine(day, temperatureUnit))
                    WeatherLine(day.weatherDescription)
                }
            }
        }

        LightBottomBar(
            items = listOf(
                settingsButton(onOpenSettings),
                LightBarButton.Text(
                    text = "TODAY",
                    onClick = onGoToToday,
                ),
                null,
            ),
        )
    }
}

@Composable
private fun SettingsContent(
    locationName: String,
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
            SelectSettingRow(
                label = "Units",
                value = temperatureUnit.displayLabel(),
                onClick = onToggleUnit,
            )
            SelectSettingRow(
                label = "Location",
                value = shortLocationName(locationName),
                onClick = onChangeLocation,
            )
        }
    }
}

@Composable
private fun SelectSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
        )
        LightText(
            text = value,
            variant = LightTextVariant.Heading,
        )
    }
}

@Composable
private fun settingsButton(onClick: () -> Unit) = LightBarButton.LightIcon(
    icon = LightIcons.SETTINGS,
    onClick = onClick,
    contentDescription = "Settings",
)

@Composable
private fun menuButton(onClick: () -> Unit) = LightBarButton.LightIcon(
    icon = LightIcons.LIST,
    onClick = onClick,
    contentDescription = "Hourly forecast",
)

@Composable
private fun WeatherLine(text: String) {
    WeatherBodyText(text)
}
