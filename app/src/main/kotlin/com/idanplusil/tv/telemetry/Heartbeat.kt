package com.idanplusil.tv.telemetry

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.content.edit
import com.idanplusil.tv.BuildConfig
import com.idanplusil.tv.IdanPlusApplication
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anonymous install-base heartbeat.
 *
 * One POST per cold start plus one every six hours via WorkManager. The payload
 * is a per-install random UUID (never a hardware identifier), the package name,
 * the version and a little nullable device context ([DeviceContext]). Nothing
 * here may ever reach the user: every failure - no network, DNS, timeout,
 * non-204 - is swallowed and the next scheduled ping simply tries again.
 */
class Heartbeat(
    private val endpoint: String,
    client: OkHttpClient,
    private val deviceId: () -> String,
    private val appId: String = BuildConfig.APPLICATION_ID,
    private val version: String = BuildConfig.VERSION_NAME,
    private val versionCode: Int = BuildConfig.VERSION_CODE,
    private val deviceContext: DeviceContext = DeviceContext.NONE,
) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /** True only on HTTP 204. Never throws; callers have nothing to handle. */
    suspend fun send(): Boolean = runCatching {
        val body = buildJsonObject {
            put("device_id", deviceId())
            put("app_id", appId)
            put("version", version)
            put("version_code", versionCode)
            // Optional context; the server accepts null for any of these but
            // rejects unknown keys, so this list must match the contract exactly.
            put("sdk_int", deviceContext.sdkInt)
            put("device", deviceContext.device)
            put("installer", deviceContext.installer)
            put("locale", deviceContext.locale)
            put("abi", deviceContext.abi)
        }.toString()
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody(JSON))
            .build()
        runInterruptible(Dispatchers.IO) {
            client.newCall(request).execute().use { it.code == 204 }
        }
    }.getOrDefault(false)

    /** Fire-and-forget from the process entry point. Returns immediately. */
    fun sendOnStart() {
        scope.launch { send() }
    }

    companion object {
        private const val WORK_NAME = "heartbeat"
        private val JSON = "application/json".toMediaType()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Schedules the six-hourly ping. The first run is a full period out, so a
         * cold start produces exactly one heartbeat, not two. UPDATE keeps the
         * existing schedule while letting a later build change the request.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/**
 * Coarse, non-identifying facts about the device, sent alongside the heartbeat
 * so the dashboard can split the install base by API level, model, installer,
 * locale and ABI. Every field is nullable; whatever cannot be read is sent as
 * JSON null. No hardware identifiers, ever.
 */
data class DeviceContext(
    val sdkInt: Int? = null,
    val device: String? = null,
    val installer: String? = null,
    val locale: String? = null,
    val abi: String? = null,
) {
    companion object {
        /** All-null context, used in tests and as the constructor default. */
        val NONE = DeviceContext()

        /**
         * Reads the context from the running system. Each field is read on its
         * own so one odd OEM failure cannot blank the rest. Strings are clipped
         * to the server's column limits; a too-long value would otherwise fail
         * the whole heartbeat with a 422.
         */
        fun fromSystem(context: Context): DeviceContext = DeviceContext(
            sdkInt = Build.VERSION.SDK_INT,
            device = runCatching { deviceLabel(Build.MANUFACTURER, Build.MODEL) }.getOrNull(),
            installer = installerPackage(context)?.clip(128),
            locale = runCatching { Locale.getDefault().toLanguageTag() }.getOrNull()?.clip(32),
            abi = runCatching { Build.SUPPORTED_ABIS.firstOrNull() }.getOrNull()?.clip(32),
        )

        /** "Xiaomi MIBOX4"; null when both parts are blank. Clipped to the server's 128-char column. */
        internal fun deviceLabel(manufacturer: String?, model: String?): String? =
            listOfNotNull(manufacturer, model).joinToString(" ").trim().clip(128)

        @Suppress("DEPRECATION")
        private fun installerPackage(context: Context): String? = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        private fun String.clip(max: Int): String? = take(max).ifEmpty { null }
    }
}

/**
 * The per-install id. Generated once, kept in its own SharedPreferences file.
 * Backup is disabled app-wide, so it does not travel to another device.
 */
class DeviceIdStore(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

    @Synchronized
    fun get(): String =
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY, it) }
        }

    private companion object {
        const val KEY = "device_id"
    }
}

/** WorkManager entry point; the real work lives in [Heartbeat.send]. */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val heartbeat = (applicationContext as? IdanPlusApplication)?.container?.heartbeat
            ?: return Result.success()
        // retry() uses WorkManager's default exponential backoff; success() lets
        // the six-hour cadence carry on.
        return if (heartbeat.send()) Result.success() else Result.retry()
    }
}
