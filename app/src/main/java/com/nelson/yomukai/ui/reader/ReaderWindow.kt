package com.nelson.yomukai.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Applies reader-only window tweaks and restores them when the reader leaves composition.
 * [brightness] < 0 means "follow the system".
 */
@Composable
fun ReaderWindowEffects(brightness: Float, keepScreenOn: Boolean, immersive: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current

    DisposableEffect(brightness) {
        val window = activity.window
        val original = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else brightness.coerceIn(0.01f, 1f)
        }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = original } }
    }

    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(immersive) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** Locks the screen orientation while the reader is shown and restores the previous setting afterwards. */
@Composable
fun ReaderOrientationEffect(lock: com.nelson.yomukai.data.prefs.OrientationLock) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(lock) {
        val original = activity.requestedOrientation
        activity.requestedOrientation = when (lock) {
            com.nelson.yomukai.data.prefs.OrientationLock.AUTO -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            com.nelson.yomukai.data.prefs.OrientationLock.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            com.nelson.yomukai.data.prefs.OrientationLock.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose { activity.requestedOrientation = original }
    }
}
