package com.thelightphone.weather

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.thelightphone.weather.R
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightGrid
import com.thelightphone.sdk.ui.LightIcon
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
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp

@InitialScreen
class WeatherHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, WeatherViewModel>(sealedActivity) {

    override val viewModelClass: Class<WeatherViewModel>
        get() = WeatherViewModel::class.java

    override fun createViewModel(): WeatherViewModel = WeatherViewModel(lightContext.dataStore)

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
                            onOpenAttribution = viewModel::openAttribution,
                        )
                    }

                    is WeatherScreenMode.Attribution -> {
                        AttributionContent(
                            onBack = viewModel::closeAttribution,
                        )
                    }

                    is WeatherScreenMode.Weekly -> {
                        WeeklyForecastContent(
                            locationName = mode.locationName,
                            days = mode.days,
                            temperatureUnit = state.temperatureUnit,
                            onSelectDay = viewModel::showDay,
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
                        val dayIndex = mode.selectedDayIndex
                        val day = mode.forecast.dayAt(dayIndex) ?: mode.forecast.today
                        val isToday = dayIndex == 0
                        WeatherContent(
                            day = day,
                            isToday = isToday,
                            currentConditions = if (isToday) mode.forecast.current else null,
                            temperatureUnit = state.temperatureUnit,
                            showPreviousDay = dayIndex > 0,
                            showNextDay = dayIndex < mode.forecast.dayCount() - 1,
                            onPreviousDay = viewModel::showPreviousDay,
                            onNextDay = viewModel::showNextDay,
                            onOpenWeekly = viewModel::openWeekly,
                            onOpenHourly = viewModel::openHourly,
                            onOpenSettings = viewModel::openSettings,
                            showHourly = isToday,
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
    isToday: Boolean,
    currentConditions: CurrentConditions?,
    temperatureUnit: TemperatureUnit,
    showPreviousDay: Boolean,
    showNextDay: Boolean,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenWeekly: () -> Unit,
    onOpenHourly: () -> Unit,
    onOpenSettings: () -> Unit,
    showHourly: Boolean = true,
) {
    val sectionPadding = Modifier.padding(bottom = 1f.gridUnitsAsDp())
    val swipeThresholdPx = with(LocalDensity.current) { 3f.gridUnitsAsDp().toPx() }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = if (showPreviousDay) {
                LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onPreviousDay,
                    contentDescription = "Previous day",
                )
            } else {
                null
            },
            center = LightTopBarCenter.Text(formatDailyTitle(day.date)),
            rightButton = if (showNextDay) {
                LightBarButton.LightIcon(
                    icon = LightIcons.ARROW_RIGHT,
                    onClick = onNextDay,
                    contentDescription = "Next day",
                )
            } else {
                null
            },
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            when {
                                totalDrag <= -swipeThresholdPx -> onNextDay()
                                totalDrag >= swipeThresholdPx -> onPreviousDay()
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            totalDrag += dragAmount
                            change.consume()
                        },
                    )
                }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            if (isToday) {
                HeroTemperatureText(
                    text = displayTemperatureC(day, currentConditions, temperatureUnit),
                )
            }

            Column(modifier = sectionPadding) {
                WeatherBodyText(displayWeatherDescription(day, currentConditions))
                HighLowTemperatureLine(day = day, unit = temperatureUnit)
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
                if (showHourly) menuButton(onClick = onOpenHourly) else null,
            ),
        )
    }
}

@Composable
private fun HighLowTemperatureLine(day: DayForecast, unit: TemperatureUnit) {
    val style = compactCopyStyle()
    val color = LightThemeTokens.colors.content
    val high = formatTemperature(day.tempMaxC, unit)
    val low = formatTemperature(day.tempMinC, unit)
    val feelsHigh = formatTemperature(day.apparentTempMaxC, unit)
    val feelsLow = formatTemperature(day.apparentTempMinC, unit)
    val iconSize = iconGridSizeForText(style)
    val iconBoxSize = with(LocalDensity.current) { style.fontSize.toDp() }
    val iconPlaceholder = Placeholder(
        width = style.fontSize,
        height = style.fontSize,
        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
    )
    val inlineContent = mapOf(
        "up" to InlineTextContent(iconPlaceholder) {
            Box(
                modifier = Modifier.size(iconBoxSize),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.UP,
                    modifier = Modifier.offset(y = 0.35f.verticalGridUnitsAsDp()),
                    size = iconSize,
                    contentDescription = "High",
                )
            }
        },
        "down" to InlineTextContent(iconPlaceholder) {
            Box(
                modifier = Modifier.size(iconBoxSize),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.DOWN,
                    modifier = Modifier.offset(y = (-0.15f).verticalGridUnitsAsDp()),
                    size = iconSize,
                    contentDescription = "Low",
                )
            }
        },
    )

    Text(
        text = buildAnnotatedString {
            appendInlineContent("up", "[up]")
            append(high)
            append(" / ")
            appendInlineContent("down", "[down]")
            append(low)
            append(" (feels like $feelsHigh / $feelsLow)")
        },
        style = style,
        color = color,
        inlineContent = inlineContent,
    )
}

@Composable
private fun iconGridSizeForText(textStyle: TextStyle): Float {
    val fontSizeDp = with(LocalDensity.current) { textStyle.fontSize.toDp().value }
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    return fontSizeDp / (screenHeightDp / LightGrid.HEIGHT)
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
private fun compactFineStyle(): TextStyle {
    val base = LightThemeTokens.typography.fine
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
    onSelectDay: (Int) -> Unit,
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
            days.forEachIndexed { index, day ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = { onSelectDay(index) })
                        .padding(bottom = 1f.gridUnitsAsDp()),
                ) {
                    WeatherBoldLine(formatDailyTitle(day.date))
                    WeatherLine(formatWeeklyHighLowLine(day, temperatureUnit))
                    WeatherDetailLine(
                        label = "Precipitation",
                        value = formatWeeklyPrecipitationDetail(day, temperatureUnit),
                    )
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
    onOpenAttribution: () -> Unit,
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
                .weight(1f)
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

        AttributionFooter(onClick = onOpenAttribution)
    }
}

@Composable
private fun AttributionFooter(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = buildAnnotatedString {
                append("Weather data provided by ")
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    append("Open-Meteo")
                }
                append(".")
            },
            style = compactFineStyle(),
            color = LightThemeTokens.colors.content,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AttributionContent(
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
            ),
            center = LightTopBarCenter.Text("Open-Meteo"),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 2f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.openmeteo),
                contentDescription = "Open-Meteo QR code",
                modifier = Modifier
                    .padding(top = 2f.gridUnitsAsDp())
                    .size(12f.gridUnitsAsDp()),
            )
            Text(
                text = "Open-Meteo is an open-source weather API\n\n" +
                    "Scan the QR code or visit:\n" +
                    "https://open-meteo.com to learn more.",
                style = compactFineStyle(),
                color = LightThemeTokens.colors.content,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2f.gridUnitsAsDp()),
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
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
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
