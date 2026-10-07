package org.legend.legendmessage.app

import android.app.Application

class App : Application() {
    lateinit var services: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        services = ServiceLocator(this)
    }

    companion object {
        lateinit var instance: App
            private set

        fun services(): ServiceLocator = instance.services
    }
}
