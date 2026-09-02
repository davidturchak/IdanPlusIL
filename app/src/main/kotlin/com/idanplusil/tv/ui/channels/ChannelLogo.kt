package com.idanplusil.tv.ui.channels

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Turns a channel's `logo` config value into something Coil can load.
 *
 * Two spellings, decided by prefix exactly the way the reference card did it:
 * `http(s)://...` is fetched; anything else is the name of a drawable bundled in
 * `res/drawable-nodpi` (`logo_11`) and resolves to its resource id. An unknown
 * name yields null and the card falls back to its monogram, so a config typo
 * costs one logo, not a crash.
 *
 * Name lookup goes through [Context.getResources]`.getIdentifier`, which is why
 * `res/raw/keep.xml` pins `@drawable/logo_*` for the resource shrinker.
 */
@Composable
fun rememberLogoModel(logo: String?): Any? {
    val context = LocalContext.current
    return remember(logo) { logoModel(context, logo) }
}

internal fun logoModel(context: Context, logo: String?): Any? {
    val value = logo?.trim().orEmpty()
    if (value.isEmpty()) return null
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    val id = context.resources.getIdentifier(value, "drawable", context.packageName)
    return id.takeIf { it != 0 }
}
