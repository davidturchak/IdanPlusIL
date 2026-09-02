package com.idanplusil.tv.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.idanplusil.tv.data.update.UpdateError
import java.io.File

sealed interface InstallLaunch {
    data object Started : InstallLaunch
    data class Refused(val reason: UpdateError) : InstallLaunch
}

/**
 * Hands a verified APK to the system package installer.
 *
 * `ACTION_VIEW` rather than a `PackageInstaller` session: for an unprivileged
 * app both end in the same system confirmation, and the session API adds a
 * receiver and PendingIntent plumbing for nothing - on success the process is
 * killed and nothing needs to be persisted.
 */
class UpdateInstaller(private val authority: String) {

    /** Below API 26 the global "unknown sources" toggle applies and the installer prompts for it. */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun launch(activity: Activity, file: File): InstallLaunch {
        // Sanity-check the archive so the user sees our message, not the
        // installer's. Also catches a debug build downloading the release APK,
        // which would install a second app instead of updating this one.
        val info = runCatching { activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0) }.getOrNull()
        if (info == null || info.packageName != activity.packageName) {
            return InstallLaunch.Refused(UpdateError.WrongPackage)
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(activity, authority, file)
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            activity.startActivity(intent)
            InstallLaunch.Started
        } catch (e: ActivityNotFoundException) {
            InstallLaunch.Refused(UpdateError.NoInstaller)
        }
    }

    /**
     * Opens the screen where the user grants this app the right to install
     * updates. Returns false when no settings screen could be opened at all
     * (some OEM TV builds), in which case the adb pre-grant is the fallback.
     */
    fun openUnknownSourcesSettings(activity: Activity): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        return candidates.any { intent ->
            runCatching { activity.startActivity(intent); true }.getOrDefault(false)
        }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
