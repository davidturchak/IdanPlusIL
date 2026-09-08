package com.idanplusil.tv

import android.app.Application
import com.idanplusil.tv.di.AppContainer
import com.idanplusil.tv.telemetry.Heartbeat

class IdanPlusApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Construction only. Nothing UI-related waits on the network here - the
        // config refresh is kicked off from the ViewModel, after the first frame
        // is already on screen from the disk cache.
        container = AppContainer(this)
        // Install-base heartbeat: one fire-and-forget POST on Dispatchers.IO per
        // cold start, and a six-hourly WorkManager job that outlives the process.
        container.heartbeat.sendOnStart()
        Heartbeat.schedulePeriodic(this)
    }
}
