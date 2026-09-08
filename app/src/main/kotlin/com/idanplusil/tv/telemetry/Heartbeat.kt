package com.idanplusil.tv.telemetry

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.idanplusil.tv.BuildConfig
import com.idanplusil.tv.IdanPlusApplication
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
 * is a per-install random UUID (never a hardware identifier), the package name
 * and the version. Nothing here may ever reach the user: every failure - no
 * network, DNS, timeout, non-204 - is swallowed and the next scheduled ping
 * simply tries again.
 */
class Heartbeat(
    private val endpoint: String,
    client: OkHttpClient,
    private val deviceId: () -> String,
    private val appId: String = BuildConfig.APPLICATION_ID,
    private val version: String = BuildConfig.VERSION_NAME,
    private val versionCode: Int = BuildConfig.VERSION_CODE,
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
 * The per-install id. Generated once, kept in its own SharedPreferences file.
 * Backup is disabled app-wide, so it does not travel to another device.
 */
class DeviceIdStore(context: Context) {
    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)

    @Synchronized
    fun get(): String =
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY, it).apply()
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
