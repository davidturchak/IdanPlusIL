package com.idanplusil.tv

import android.app.Application
import com.idanplusil.tv.di.AppContainer

class IdanPlusApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Construction only. Nothing network-related happens here - the config
        // refresh is kicked off from the ViewModel, after the first frame is
        // already on screen from the disk cache.
        container = AppContainer(this)
    }
}
