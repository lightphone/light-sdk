package com.thelightphone.weather

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class WeatherScreenMode {
    data object LocationInput : WeatherScreenMode()
    data object Loading : WeatherScreenMode()
    data class Settings(val locationName: String) : WeatherScreenMode()
    data object Attribution : WeatherScreenMode()
    data class Weekly(
        val locationName: String,
        val days: List<WeeklyDay>,
        val selectedDayIndex: Int,
    ) : WeatherScreenMode()

    data class Hourly(
        val locationName: String,
        val forecast: StoredForecast,
    ) : WeatherScreenMode()

    data class Weather(
        val locationName: String,
        val forecast: StoredForecast,
        val selectedDayIndex: Int = 0,
    ) : WeatherScreenMode()
}

data class WeatherUiState(
    val mode: WeatherScreenMode = WeatherScreenMode.Loading,
    val canCancelLocationInput: Boolean = false,
    val locationInputSession: Int = 0,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.Celsius,
    val errorModal: String? = null,
)

private enum class LocationInputSource {
    Initial,
    Settings,
}

private const val NETWORK_ERROR_MESSAGE =
    "The Weather tool requires a network connection. Please insert a data sim or connect to wi-fi to view the latest conditions."

class WeatherViewModel(
    private val dataStore: DataStore<Preferences>,
) : LightViewModel<Unit>() {
    private val api = WeatherApi()
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private var lastSelectedDayIndex: Int = 0
    private var savedLocationQuery: String = ""
    private var cachedLocationName: String = ""
    private var locationInputSource: LocationInputSource = LocationInputSource.Initial
    private var screenBeforeSettings: WeatherScreenMode? = null

    private val apiExceptionHandler = CoroutineExceptionHandler { _, _ ->
        viewModelScope.launch(Dispatchers.Main) {
            showApiFailure()
        }
    }

    private fun settingsMode(): WeatherScreenMode.Settings =
        WeatherScreenMode.Settings(cachedLocationName)

    private fun WeatherUiState.openLocationInput(
        canCancel: Boolean = canCancelLocationInput,
    ): WeatherUiState = copy(
        mode = WeatherScreenMode.LocationInput,
        canCancelLocationInput = canCancel,
        locationInputSession = locationInputSession + 1,
        errorModal = null,
    )

    private suspend fun updateState(transform: (WeatherUiState) -> WeatherUiState) {
        withContext(Dispatchers.Main) {
            _uiState.update(transform)
        }
    }

    private suspend fun setState(state: WeatherUiState) {
        withContext(Dispatchers.Main) {
            _uiState.value = state
        }
    }

    private suspend fun showLocationInputError(message: String) {
        updateState {
            it.openLocationInput(canCancel = it.canCancelLocationInput).copy(errorModal = message)
        }
    }

    private suspend fun showApiFailure(error: Throwable? = null) {
        val message = when (error) {
            is LocationNotFoundException -> "Location not found."
            else -> NETWORK_ERROR_MESSAGE
        }
        when {
            locationInputSource == LocationInputSource.Settings -> {
                updateState {
                    it.copy(mode = settingsMode(), errorModal = message)
                }
            }
            _uiState.value.canCancelLocationInput -> restoreSavedWeather(message)
            else -> showLocationInputError(message)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO + apiExceptionHandler) {
            runCatching { loadStoredState() }
                .onFailure {
                    showLocationInputError("Could not load weather.")
                }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (skipRefreshOnNextScreenShow) {
            skipRefreshOnNextScreenShow = false
            return
        }
        resetToTodayView()
        viewModelScope.launch(Dispatchers.IO + apiExceptionHandler) {
            refreshForecastOnScreenShow()
        }
    }

    private var skipRefreshOnNextScreenShow = true

    private fun resetToTodayView() {
        lastSelectedDayIndex = 0
        val state = _uiState.value
        when (val mode = state.mode) {
            is WeatherScreenMode.Weather -> {
                _uiState.value = state.copy(mode = mode.copy(selectedDayIndex = 0))
            }
            is WeatherScreenMode.Hourly -> {
                _uiState.value = state.copy(
                    mode = WeatherScreenMode.Weather(
                        locationName = mode.locationName,
                        forecast = mode.forecast,
                        selectedDayIndex = 0,
                    ),
                )
            }
            is WeatherScreenMode.Weekly,
            is WeatherScreenMode.Settings,
            is WeatherScreenMode.Attribution -> restoreWeatherScreen(selectedDayIndex = 0)
            else -> Unit
        }
    }

    private suspend fun loadStoredState() {
        val prefs = dataStore.data.first()
        val unit = temperatureUnitFromStorage(prefs[WeatherPreferences.TEMPERATURE_UNIT])
        val query = prefs[WeatherPreferences.LOCATION_QUERY]
        val locationName = prefs[WeatherPreferences.LOCATION_NAME]
        val lat = prefs[WeatherPreferences.LATITUDE]?.toDoubleOrNull()
        val lon = prefs[WeatherPreferences.LONGITUDE]?.toDoubleOrNull()
        val forecastJson = prefs[WeatherPreferences.FORECAST_JSON]

        updateState { it.copy(temperatureUnit = unit) }

        if (query == null || locationName == null || lat == null || lon == null) {
            updateState { it.copy(temperatureUnit = unit).openLocationInput(canCancel = false) }
            return
        }

        val cachedForecast = forecastJson?.let {
            runCatching { json.decodeFromString<StoredForecast>(it) }.getOrNull()
        }
        savedLocationQuery = query
        cachedLocationName = locationName
        if (cachedForecast != null && cachedForecast.weekly.isNotEmpty()) {
            setState(
                WeatherUiState(
                    mode = WeatherScreenMode.Weather(
                        locationName = locationName,
                        forecast = cachedForecast,
                    ),
                    canCancelLocationInput = true,
                    temperatureUnit = unit,
                ),
            )
        } else {
            updateState {
                it.copy(mode = WeatherScreenMode.Loading, temperatureUnit = unit)
            }
        }

        val needsRefresh = cachedForecast == null ||
            cachedForecast.weekly.isEmpty() ||
            cachedForecast.hourly.isEmpty()
        refreshForecast(query, locationName, lat, lon, showLoadingScreen = needsRefresh)
    }

    private suspend fun refreshForecastOnScreenShow() {
        val prefs = dataStore.data.first()
        val query = prefs[WeatherPreferences.LOCATION_QUERY] ?: return
        val locationName = prefs[WeatherPreferences.LOCATION_NAME] ?: return
        val lat = prefs[WeatherPreferences.LATITUDE]?.toDoubleOrNull() ?: return
        val lon = prefs[WeatherPreferences.LONGITUDE]?.toDoubleOrNull() ?: return
        refreshForecast(
            query = query,
            locationName = locationName,
            latitude = lat,
            longitude = lon,
            showLoadingScreen = false,
        )
    }

    fun submitLocation(rawQuery: CharSequence) {
        val query = rawQuery.toString().trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(errorModal = "Please enter a location.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO + apiExceptionHandler) {
            runCatching {
                updateState {
                    it.copy(
                        mode = WeatherScreenMode.Loading,
                        errorModal = null,
                    )
                }

                val geoResult = api.resolveLocation(query)
                geoResult.fold(
                    onSuccess = { geo ->
                        refreshForecast(
                            query = query,
                            locationName = geo.displayName(),
                            latitude = geo.latitude,
                            longitude = geo.longitude,
                            showLoadingScreen = true,
                        )
                    },
                    onFailure = { error ->
                        showApiFailure(error)
                    },
                )
            }.onFailure {
                showApiFailure()
            }
        }
    }

    private suspend fun refreshForecast(
        query: String,
        locationName: String,
        latitude: Double,
        longitude: Double,
        showLoadingScreen: Boolean,
    ) {
        if (showLoadingScreen) {
            updateState { it.copy(mode = WeatherScreenMode.Loading) }
        }

        val forecastResult = api.fetchForecast(latitude, longitude)
        forecastResult.fold(
            onSuccess = { forecast ->
                try {
                    dataStore.edit { prefs ->
                        prefs[WeatherPreferences.LOCATION_QUERY] = query
                        prefs[WeatherPreferences.LOCATION_NAME] = locationName
                        prefs[WeatherPreferences.LATITUDE] = latitude.toString()
                        prefs[WeatherPreferences.LONGITUDE] = longitude.toString()
                        prefs[WeatherPreferences.FORECAST_JSON] = json.encodeToString(forecast)
                    }
                } catch (_: Exception) {
                }
                savedLocationQuery = query
                cachedLocationName = locationName
                locationInputSource = LocationInputSource.Initial
                updateState { state ->
                    state.copy(
                        mode = WeatherScreenMode.Weather(
                            locationName = locationName,
                            forecast = forecast,
                            selectedDayIndex = lastSelectedDayIndex,
                        ),
                        canCancelLocationInput = true,
                    )
                }
            },
            onFailure = { error ->
                showApiFailure(error)
            },
        )
    }

    fun showPreviousDay() {
        _uiState.update { state ->
            val mode = state.mode as? WeatherScreenMode.Weather ?: return@update state
            val previousIndex = mode.selectedDayIndex - 1
            if (previousIndex < 0) return@update state
            lastSelectedDayIndex = previousIndex
            state.copy(mode = mode.copy(selectedDayIndex = previousIndex))
        }
    }

    fun showNextDay() {
        _uiState.update { state ->
            val mode = state.mode as? WeatherScreenMode.Weather ?: return@update state
            val nextIndex = mode.selectedDayIndex + 1
            if (nextIndex >= mode.forecast.dayCount()) return@update state
            lastSelectedDayIndex = nextIndex
            state.copy(mode = mode.copy(selectedDayIndex = nextIndex))
        }
    }

    fun openSettings() {
        val current = _uiState.value.mode
        if (current !is WeatherScreenMode.Settings) {
            screenBeforeSettings = current
        }
        val locationName = when (current) {
            is WeatherScreenMode.Weather -> current.locationName
            is WeatherScreenMode.Weekly -> current.locationName
            is WeatherScreenMode.Hourly -> current.locationName
            is WeatherScreenMode.Settings -> current.locationName
            else -> cachedLocationName
        }
        _uiState.update {
            it.copy(mode = WeatherScreenMode.Settings(locationName), errorModal = null)
        }
    }

    fun closeSettings() {
        val previous = screenBeforeSettings
        screenBeforeSettings = null
        if (previous != null && previous !is WeatherScreenMode.Settings) {
            _uiState.update { it.copy(mode = previous, errorModal = null) }
        } else {
            restoreWeatherScreen()
        }
    }

    fun openWeekly() {
        _uiState.update { state ->
            val (locationName, weekly) = when (val mode = state.mode) {
                is WeatherScreenMode.Weather -> mode.locationName to mode.forecast.weekly
                is WeatherScreenMode.Hourly -> mode.locationName to mode.forecast.weekly
                else -> return@update state
            }
            if (weekly.isEmpty()) return@update state
            state.copy(
                mode = WeatherScreenMode.Weekly(
                    locationName = locationName,
                    days = weekly,
                    selectedDayIndex = lastSelectedDayIndex,
                ),
            )
        }
    }

    fun showDay(index: Int) {
        val state = _uiState.value
        val weekly = state.mode as? WeatherScreenMode.Weekly ?: return
        if (index < 0 || index >= weekly.days.size) return
        lastSelectedDayIndex = index
        restoreWeatherScreen(selectedDayIndex = index)
    }

    fun openHourly() {
        _uiState.update { state ->
            val weather = state.mode as? WeatherScreenMode.Weather ?: return@update state
            if (weather.forecast.hoursForToday().isEmpty()) return@update state
            state.copy(
                mode = WeatherScreenMode.Hourly(
                    locationName = weather.locationName,
                    forecast = weather.forecast,
                ),
            )
        }
    }

    fun closeHourly() {
        restoreWeatherScreen()
    }

    fun closeWeekly() {
        restoreWeatherScreen()
    }

    fun goToToday() {
        lastSelectedDayIndex = 0
        when (_uiState.value.mode) {
            is WeatherScreenMode.Hourly -> closeHourly()
            else -> restoreWeatherScreen(selectedDayIndex = 0)
        }
    }

    fun openAttribution() {
        _uiState.update { it.copy(mode = WeatherScreenMode.Attribution, errorModal = null) }
    }

    fun closeAttribution() {
        _uiState.update { it.copy(mode = settingsMode(), errorModal = null) }
    }

    fun openLocationFromSettings() {
        locationInputSource = LocationInputSource.Settings
        _uiState.update { it.openLocationInput(canCancel = true) }
    }

    fun toggleTemperatureUnit() {
        val next = _uiState.value.temperatureUnit.toggle()
        _uiState.update { it.copy(temperatureUnit = next) }
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs ->
                prefs[WeatherPreferences.TEMPERATURE_UNIT] = next.storageValue()
            }
        }
    }

    fun cancelLocationInput() {
        if (!_uiState.value.canCancelLocationInput) return
        when (locationInputSource) {
            LocationInputSource.Settings -> {
                locationInputSource = LocationInputSource.Initial
                _uiState.update { it.copy(mode = settingsMode()) }
            }
            LocationInputSource.Initial -> {
                viewModelScope.launch(Dispatchers.IO) {
                    restoreSavedWeather()
                }
            }
        }
    }

    private fun restoreWeatherScreen(selectedDayIndex: Int? = null) {
        val state = _uiState.value
        val weather = state.mode as? WeatherScreenMode.Weather
        if (weather != null) return
        val weekly = state.mode as? WeatherScreenMode.Weekly
        val hourly = state.mode as? WeatherScreenMode.Hourly
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = dataStore.data.first()
                val locationName = prefs[WeatherPreferences.LOCATION_NAME] ?: return@runCatching
                val forecastJson = prefs[WeatherPreferences.FORECAST_JSON] ?: return@runCatching
                val forecast = runCatching { json.decodeFromString<StoredForecast>(forecastJson) }.getOrNull()
                    ?: return@runCatching
                updateState {
                    it.copy(
                        mode = WeatherScreenMode.Weather(
                            locationName = locationName,
                            forecast = forecast,
                            selectedDayIndex = selectedDayIndex
                                ?: weekly?.selectedDayIndex
                                ?: lastSelectedDayIndex,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun restoreSavedWeather(errorMessage: String? = null) {
        try {
            val prefs = dataStore.data.first()
            val locationName = prefs[WeatherPreferences.LOCATION_NAME]
            val forecastJson = prefs[WeatherPreferences.FORECAST_JSON]
            val query = prefs[WeatherPreferences.LOCATION_QUERY]
            if (locationName == null || forecastJson == null || query == null) {
                showLocationInputError(errorMessage ?: "Could not load weather.")
                return
            }
            val forecast = runCatching { json.decodeFromString<StoredForecast>(forecastJson) }.getOrNull()
            if (forecast == null) {
                showLocationInputError(errorMessage ?: "Could not load weather.")
                return
            }
            savedLocationQuery = query
            cachedLocationName = locationName
            locationInputSource = LocationInputSource.Initial
            setState(
                WeatherUiState(
                    mode = WeatherScreenMode.Weather(
                        locationName = locationName,
                        forecast = forecast,
                        selectedDayIndex = lastSelectedDayIndex,
                    ),
                    canCancelLocationInput = true,
                    temperatureUnit = _uiState.value.temperatureUnit,
                    errorModal = errorMessage,
                ),
            )
        } catch (_: Exception) {
            showLocationInputError(errorMessage ?: "Could not load weather.")
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }

    override fun onCleared() {
        super.onCleared()
        api.close()
    }
}
