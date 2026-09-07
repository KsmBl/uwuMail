package de.uwumail.data.settings

import android.content.Context
import de.uwumail.core.AppTheme
import de.uwumail.core.SwipeAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads and writes [AppSettings].
 *
 * Backed by SharedPreferences rather than DataStore because two of the call
 * sites cannot suspend: the WebView's resource interceptor runs on a background
 * thread with no scope, and the sync worker needs the window before it starts.
 * The current value is mirrored in a StateFlow so Compose still observes it.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("uwumail_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** The current snapshot, for callers that cannot collect a flow. */
    val current: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        prefs.edit().apply {
            putBoolean(UNSUBSCRIBE, next.unsubscribeBanner)
            putBoolean(STRIP_TRACKING, next.askStripTracking)
            putBoolean(PRELOAD, next.preloadUnread)
            putBoolean(BLOCK_IMAGES, next.blockRemoteImages)
            putBoolean(ALLOW_JS, next.allowJavaScript)
            putBoolean(FILTER_TINY, next.filterTinyImages)
            putInt(MIN_WIDTH, next.minImageWidth)
            putInt(MIN_HEIGHT, next.minImageHeight)
            putString(THEME, next.theme.name)
            putString(SWIPE_RIGHT, next.swipeRight.name)
            putString(SWIPE_LEFT, next.swipeLeft.name)
            putInt(SWIPE_THRESHOLD, next.swipeThresholdPercent)
            putBoolean(CONVERSATIONS, next.groupIntoConversations)
            putInt(TEXT_ZOOM, next.readerTextZoom)
            putBoolean(GRAVITY, next.gravityUnlocked)
            putBoolean(WINDOW_ON, next.syncWindowEnabled)
            putStringSet(WINDOW_DAYS, next.syncDays.map(Int::toString).toSet())
            putInt(WINDOW_START, next.syncStartMinutes)
            putInt(WINDOW_END, next.syncEndMinutes)
        }.apply()
        _state.value = next
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            unsubscribeBanner = prefs.getBoolean(UNSUBSCRIBE, defaults.unsubscribeBanner),
            askStripTracking = prefs.getBoolean(STRIP_TRACKING, defaults.askStripTracking),
            preloadUnread = prefs.getBoolean(PRELOAD, defaults.preloadUnread),
            blockRemoteImages = prefs.getBoolean(BLOCK_IMAGES, defaults.blockRemoteImages),
            allowJavaScript = prefs.getBoolean(ALLOW_JS, defaults.allowJavaScript),
            filterTinyImages = prefs.getBoolean(FILTER_TINY, defaults.filterTinyImages),
            minImageWidth = prefs.getInt(MIN_WIDTH, defaults.minImageWidth),
            minImageHeight = prefs.getInt(MIN_HEIGHT, defaults.minImageHeight),
            theme = prefs.getString(THEME, null)?.let(AppTheme::of) ?: defaults.theme,
            swipeRight = prefs.getString(SWIPE_RIGHT, null)
                ?.let(SwipeAction::of) ?: defaults.swipeRight,
            swipeLeft = prefs.getString(SWIPE_LEFT, null)
                ?.let(SwipeAction::of) ?: defaults.swipeLeft,
            swipeThresholdPercent = AppSettings.clampSwipePercent(
                prefs.getInt(SWIPE_THRESHOLD, defaults.swipeThresholdPercent)
            ),
            groupIntoConversations = prefs.getBoolean(
                CONVERSATIONS, defaults.groupIntoConversations
            ),
            readerTextZoom = AppSettings.clampTextZoom(
                prefs.getInt(TEXT_ZOOM, defaults.readerTextZoom)
            ),
            gravityUnlocked = prefs.getBoolean(GRAVITY, defaults.gravityUnlocked),
            syncWindowEnabled = prefs.getBoolean(WINDOW_ON, defaults.syncWindowEnabled),
            syncDays = prefs.getStringSet(WINDOW_DAYS, null)
                ?.mapNotNull(String::toIntOrNull)?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: defaults.syncDays,
            syncStartMinutes = prefs.getInt(WINDOW_START, defaults.syncStartMinutes),
            syncEndMinutes = prefs.getInt(WINDOW_END, defaults.syncEndMinutes)
        )
    }

    private companion object {
        const val UNSUBSCRIBE = "unsubscribe_banner"
        const val STRIP_TRACKING = "strip_tracking"
        const val PRELOAD = "preload_unread"
        const val BLOCK_IMAGES = "block_remote_images"
        const val ALLOW_JS = "allow_javascript"
        const val FILTER_TINY = "filter_tiny_images"
        const val MIN_WIDTH = "min_image_width"
        const val MIN_HEIGHT = "min_image_height"
        const val THEME = "app_theme"
        const val SWIPE_RIGHT = "swipe_right_action"
        const val SWIPE_LEFT = "swipe_left_action"
        const val SWIPE_THRESHOLD = "swipe_threshold_percent"
        const val CONVERSATIONS = "group_into_conversations"
        const val TEXT_ZOOM = "reader_text_zoom"
        const val GRAVITY = "gravity_unlocked"
        const val WINDOW_ON = "sync_window_enabled"
        const val WINDOW_DAYS = "sync_window_days"
        const val WINDOW_START = "sync_window_start"
        const val WINDOW_END = "sync_window_end"
    }
}
