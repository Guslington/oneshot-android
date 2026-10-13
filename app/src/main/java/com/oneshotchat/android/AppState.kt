package com.oneshotchat.android

/** Process-wide flags shared between the UI and the background service. */
object AppState {
    /** True while MainActivity is visible; the service skips notifications then. */
    @Volatile var foreground: Boolean = false
}
