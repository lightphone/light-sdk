package com.thelightphone.sdk.ui

sealed class LightIconConfiguration(
    val name: String,
    val drawableResource: Int,
)

object LightIcons {
    object ACCEPT : LightIconConfiguration(
        name = "confirm",
        drawableResource = R.drawable.ic_accept_white,
    )
    object ADD : LightIconConfiguration(
        name = "Add",
        drawableResource = R.drawable.ic_add_white,
    )
    object AIRPLANE : LightIconConfiguration(
        name = "airplane",
        drawableResource = R.drawable.ic_airplane_white,
    )
    object ALARM : LightIconConfiguration(
        name = "alarm",
        drawableResource = R.drawable.ic_alarm_white,
    )
    object ARROW_DOWN : LightIconConfiguration(
        name = "arrow pointing down",
        drawableResource = R.drawable.ic_arrow_down_white,
    )
    object AUDIO_MESSAGE : LightIconConfiguration(
        name = "audio message",
        drawableResource = R.drawable.ic_audio_message_white,
    )
    object BACK : LightIconConfiguration(
        name = "back",
        drawableResource = R.drawable.ic_back_white,
    )
    object BATTERY_ERROR : LightIconConfiguration(
        name = "battery empty",
        drawableResource = R.drawable.ic_battery_error_white,
    )
    object BATTERY_EMPTY : LightIconConfiguration(
        name = "battery empty",
        drawableResource = R.drawable.ic_battery_empty_white,
    )
    object BATTERY_ONE_QUARTER : LightIconConfiguration(
        name = "battery one quarter full",
        drawableResource = R.drawable.ic_battery_one_quarter_white,
    )
    object BATTERY_HALF : LightIconConfiguration(
        name = "battery half full",
        drawableResource = R.drawable.ic_battery_half_white,
    )
    object BATTERY_THREE_QUARTERS : LightIconConfiguration(
        name = "battery three quarters full",
        drawableResource = R.drawable.ic_battery_three_quarters_white,
    )
    object BATTERY_FULL : LightIconConfiguration(
        name = "full battery",
        drawableResource = R.drawable.ic_battery_full_white,
    )
    object BATTERY_ALMOST_FULL : LightIconConfiguration(
        name = "almost full battery",
        drawableResource = R.drawable.ic_battery_almost_full_white,
    )
    object BATTERY_CHARGING : LightIconConfiguration(
        name = "battery charging",
        drawableResource = R.drawable.ic_battery_charging_white,
    )
    object BLUETOOTH : LightIconConfiguration(
        name = "bluetooth",
        drawableResource = R.drawable.ic_bluetooth_white,
    )
    object CALL : LightIconConfiguration(
        name = "call",
        drawableResource = R.drawable.ic_call_white,
    )
    object CALL_MISSED : LightIconConfiguration(
        name = "mute",
        drawableResource = R.drawable.ic_missed_call_white,
    )
    object CAMERA_BRIGHTNESS : LightIconConfiguration(
        name = "camera brightness",
        drawableResource = R.drawable.ic_camera_brightness,
    )
    object CAMERA : LightIconConfiguration(
        name = "camera",
        drawableResource = R.drawable.ic_camera,
    )
    object CAMERA_FLASH_ON : LightIconConfiguration(
        name = "camera flash on",
        drawableResource = R.drawable.ic_camera_flash_on,
    )
    object CAMERA_FLASH_OFF : LightIconConfiguration(
        name = "camera flash off",
        drawableResource = R.drawable.ic_camera_flash_off,
    )
    object CAMERA_FLASH_AUTO : LightIconConfiguration(
        name = "camera flash auto",
        drawableResource = R.drawable.ic_camera_flash_auto,
    )
    object CAMERA_LANDSCAPE : LightIconConfiguration(
        name = "camera landscape",
        drawableResource = R.drawable.ic_camera_landscape,
    )
    object CAMERA_SETTINGS : LightIconConfiguration(
        name = "camera settings",
        drawableResource = R.drawable.ic_camera_settings,
    )
    object CAMERA_RECORDING : LightIconConfiguration(
        name = "camera recording",
        drawableResource = R.drawable.ic_camera_recording,
    )
    object CAMERA_FOCUS_LOCKING : LightIconConfiguration(
        name = "camera focus locking",
        drawableResource = R.drawable.ic_camera_focus_locking,
    )
    object CAMERA_FOCUS_LOCKED : LightIconConfiguration(
        name = "camera focus locked",
        drawableResource = R.drawable.ic_camera_focus_locked,
    )
    object CE_MARK : LightIconConfiguration(
        name = "ce mark",
        drawableResource = R.drawable.ic_ce_mark_white,
    )
    object CIRCLE : LightIconConfiguration(
        name = "circle",
        drawableResource = R.drawable.ic_circle_white,
    )
    object CLOSE : LightIconConfiguration(
        name = "close",
        drawableResource = R.drawable.ic_close_white,
    )
    object COMPOSE_MESSAGE : LightIconConfiguration(
        name = "compose message",
        drawableResource = R.drawable.ic_compose_white,
    )
    object PENCIL : LightIconConfiguration(
        name = "pencil",
        drawableResource = R.drawable.ic_pencil_white,
    )
    object DELETE : LightIconConfiguration(
        name = "delete",
        drawableResource = R.drawable.ic_delete_white,
    )
    object DENY : LightIconConfiguration(
        name = "deny",
        drawableResource = R.drawable.ic_deny_white,
    )
    object DIALPAD : LightIconConfiguration(
        name = "dialpad",
        drawableResource = R.drawable.ic_dialpad_white,
    )
    object DIRECTIONS_ARRIVAL : LightIconConfiguration(
        name = "arrival",
        drawableResource = R.drawable.ic_directions_arrival_white,
    )
    object DIRECTIONS_LEFT : LightIconConfiguration(
        name = "left turn",
        drawableResource = R.drawable.ic_directions_left_white,
    )
    object DIRECTIONS_RIGHT : LightIconConfiguration(
        name = "right turn",
        drawableResource = R.drawable.ic_directions_right_white,
    )
    object DIRECTIONS_SLIGHT_LEFT : LightIconConfiguration(
        name = "slight left turn",
        drawableResource = R.drawable.ic_directions_slight_left_white,
    )
    object DIRECTIONS_SLIGHT_RIGHT : LightIconConfiguration(
        name = "slight right turn",
        drawableResource = R.drawable.ic_directions_slight_right_white,
    )
    object DIRECTIONS_MIDDLE_FORK : LightIconConfiguration(
        name = "middle fork",
        drawableResource = R.drawable.ic_directions_middle_fork_white,
    )
    object DIRECTIONS_STRAIGHT : LightIconConfiguration(
        name = "continue straight",
        drawableResource = R.drawable.ic_directions_straight_white,
    )
    object DIRECTIONS_BUS : LightIconConfiguration(
        name = "bus",
        drawableResource = R.drawable.ic_directions_bus_white,
    )
    object DIRECTIONS_SUBWAY : LightIconConfiguration(
        name = "subway",
        drawableResource = R.drawable.ic_directions_subway_white,
    )
    object DIRECTIONS_TRAIN : LightIconConfiguration(
        name = "train",
        drawableResource = R.drawable.ic_directions_train_white,
    )
    object DIRECTIONS_PEDESTRIAN : LightIconConfiguration(
        name = "pedestrian",
        drawableResource = R.drawable.ic_directions_pedestrian_white,
    )
    object DIRECTIONS_ROUNDABOUT : LightIconConfiguration(
        name = "roundabout",
        drawableResource = R.drawable.ic_directions_round_about_white,
    )
    object DIRECTIONS_U_TURN_RIGHT : LightIconConfiguration(
        name = "u turn right",
        drawableResource = R.drawable.ic_directions_uturn_right_white,
    )
    object DIRECTIONS_U_TURN_LEFT : LightIconConfiguration(
        name = "u turn left",
        drawableResource = R.drawable.ic_directions_uturn_left_white,
    )
    object DIRECTIONS_FERRY : LightIconConfiguration(
        name = "ferry",
        drawableResource = R.drawable.ic_directions_ferry_white,
    )
    object DOWN : LightIconConfiguration(
        name = "down",
        drawableResource = R.drawable.ic_down_white,
    )
    object EMERGENCY : LightIconConfiguration(
        name = "emergency",
        drawableResource = R.drawable.ic_emergency_white,
    )
    object FCC_MARK : LightIconConfiguration(
        name = "fcc mark",
        drawableResource = R.drawable.ic_fcc_mark_white,
    )
    object LIGHT_LOGO : LightIconConfiguration(
        name = "light logo",
        drawableResource = R.drawable.ic_light_logo_white,
    )
    object FAST_FORWARD : LightIconConfiguration(
        name = "fast-forward",
        drawableResource = R.drawable.ic_fast_forward_white,
    )
    object LIST : LightIconConfiguration(
        name = "list",
        drawableResource = R.drawable.ic_list_white,
    )
    object LOOP : LightIconConfiguration(
        name = "loop",
        drawableResource = R.drawable.ic_loop_white,
    )
    object MEDIA : LightIconConfiguration(
        name = "media",
        drawableResource = R.drawable.ic_media_white,
    )
    object MICROPHONE : LightIconConfiguration(
        name = "microphone",
        drawableResource = R.drawable.ic_microphone_white,
    )
    object MUTE : LightIconConfiguration(
        name = "mute",
        drawableResource = R.drawable.ic_mute_white,
    )
    object PAUSE : LightIconConfiguration(
        name = "pause",
        drawableResource = R.drawable.ic_pause_white,
    )
    object PLAY : LightIconConfiguration(
        name = "play",
        drawableResource = R.drawable.ic_play_white,
    )
    object REWIND : LightIconConfiguration(
        name = "rewind",
        drawableResource = R.drawable.ic_rewind_white,
    )
    object SAVE_TO_ALBUM : LightIconConfiguration(
        name = "save to album",
        drawableResource = R.drawable.ic_save_to_album,
    )
    object SEARCH : LightIconConfiguration(
        name = "search",
        drawableResource = R.drawable.ic_search_white,
    )
    object SELECT_OFF : LightIconConfiguration(
        name = "select off",
        drawableResource = R.drawable.ic_select_off_white,
    )
    object SELECT_ON : LightIconConfiguration(
        name = "select on",
        drawableResource = R.drawable.ic_select_on_white,
    )
    object SEND : LightIconConfiguration(
        name = "send",
        drawableResource = R.drawable.ic_send_white,
    )
    object SETTINGS : LightIconConfiguration(
        name = "settings",
        drawableResource = R.drawable.ic_settings_white,
    )
    object SHUFFLE : LightIconConfiguration(
        name = "shuffle",
        drawableResource = R.drawable.ic_shuffle_white,
    )
    object SIGNAL_1 : LightIconConfiguration(
        name = "signal 1 bar",
        drawableResource = R.drawable.ic_signal1_bar_white,
    )
    object SIGNAL_2 : LightIconConfiguration(
        name = "signal 2 bars",
        drawableResource = R.drawable.ic_signal2_bars_white,
    )
    object SIGNAL_3 : LightIconConfiguration(
        name = "signal 3 bars",
        drawableResource = R.drawable.ic_signal3_bars_white,
    )
    object SIGNAL_4 : LightIconConfiguration(
        name = "signal 4 bars",
        drawableResource = R.drawable.ic_signal4_bars_white,
    )
    object SIGNAL_NONE : LightIconConfiguration(
        name = "no signal",
        drawableResource = R.drawable.ic_signal_none_white,
    )
    object SPEAKER : LightIconConfiguration(
        name = "speaker",
        drawableResource = R.drawable.ic_speaker_on,
    )
    object STAR : LightIconConfiguration(
        name = "star",
        drawableResource = R.drawable.ic_star_white,
    )
    object STAR_OUTLINE : LightIconConfiguration(
        name = "star outline",
        drawableResource = R.drawable.ic_star_outline_white,
    )
    object TETHERING : LightIconConfiguration(
        name = "tethering enabled",
        drawableResource = R.drawable.ic_tethering_white,
    )
    object TOGGLE_STATE_OFF : LightIconConfiguration(
        name = "toggle state off",
        drawableResource = R.drawable.ic_toggle_state_off_white,
    )
    object TOGGLE_STATE_ON : LightIconConfiguration(
        name = "toggle state on",
        drawableResource = R.drawable.ic_toggle_state_on_white,
    )
    object UP : LightIconConfiguration(
        name = "up",
        drawableResource = R.drawable.ic_up_white,
    )
    object VOICE_MAIL : LightIconConfiguration(
        name = "voicemail",
        drawableResource = R.drawable.ic_voice_mail_white,
    )
    object VOICE_MEMO : LightIconConfiguration(
        name = "voicememo",
        drawableResource = R.drawable.ic_voice_memo_white,
    )
    object WEEE_MARK : LightIconConfiguration(
        name = "weee mark",
        drawableResource = R.drawable.ic_weee_mark_white,
    )
    object WIFI : LightIconConfiguration(
        name = "wifi",
        drawableResource = R.drawable.ic_wifi_white,
    )
    object WIFI_NO_INTERNET : LightIconConfiguration(
        name = "wifi no internet",
        drawableResource = R.drawable.ic_wifi_no_internet_white,
    )
    object LARGE_LIST : LightIconConfiguration(
        name = "large list",
        drawableResource = R.drawable.ic_large_list_white,
    )
    object DOWNLOADED_ARROW : LightIconConfiguration(
        name = "downloaded arrow",
        drawableResource = R.drawable.ic_downloaded_arrow_white,
    )
    object DOWNLOAD_ARROW : LightIconConfiguration(
        name = "download arrow",
        drawableResource = R.drawable.ic_download_arrow_white,
    )
    object SKIP_BACKWARD_FIFTEEN : LightIconConfiguration(
        name = "skip backward fifteen",
        drawableResource = R.drawable.ic_skip_backward_fifteen_white,
    )
    object SKIP_FORWARD_FIFTEEN : LightIconConfiguration(
        name = "skip forward fifteen",
        drawableResource = R.drawable.ic_skip_forward_fifteen_white,
    )
    object REFRESH : LightIconConfiguration(
        name = "refresh",
        drawableResource = R.drawable.ic_refresh_white,
    )
    object MAP : LightIconConfiguration(
        name = "map",
        drawableResource = R.drawable.ic_map_white,
    )
    object CROSSHAIR : LightIconConfiguration(
        name = "crosshair",
        drawableResource = R.drawable.ic_crosshair_white,
    )
    object ARROW_RIGHT : LightIconConfiguration(
        name = "arrow right",
        drawableResource = R.drawable.ic_arrow_right_white,
    )
    object STOP : LightIconConfiguration(
        name = "stop",
        drawableResource = R.drawable.ic_stop_white,
    )
    object CONTACTS : LightIconConfiguration(
        name = "contacts",
        drawableResource = R.drawable.ic_contacts_white,
    )
    object REVERSE_ORDER : LightIconConfiguration(
        name = "reverse order",
        drawableResource = R.drawable.ic_reverse_order_white,
    )
    object ELLIPSES : LightIconConfiguration(
        name = "ellipses",
        drawableResource = R.drawable.ic_ellipse_white,
    )
    object SPACER : LightIconConfiguration(
        name = "spacer",
        drawableResource = R.drawable.ic_spacer,
    )
    object TRASH : LightIconConfiguration(
        name = "trash",
        drawableResource = R.drawable.ic_trash,
    )
    object SPEAKER_ON : LightIconConfiguration(
        name = "speaker on",
        drawableResource = R.drawable.ic_speaker_on,
    )
    object SPEAKER_MUTED : LightIconConfiguration(
        name = "speaker muted",
        drawableResource = R.drawable.ic_speaker_muted,
    )
    object ROTATE : LightIconConfiguration(
        name = "rotate",
        drawableResource = R.drawable.ic_rotate_white,
    )

    val allEntries: List<Pair<String, LightIconConfiguration>> by lazy {
        LightIcons::class.java.declaredClasses
            .asSequence()
            .filter { LightIconConfiguration::class.java.isAssignableFrom(it) }
            .mapNotNull { iconClass ->
                val instance = runCatching {
                    val instanceField = iconClass.getDeclaredField("INSTANCE")
                    instanceField.isAccessible = true
                    instanceField.get(null) as LightIconConfiguration
                }.getOrNull() ?: return@mapNotNull null
                iconClass.simpleName to instance
            }
            .sortedBy { it.first }
            .toList()
    }
}
