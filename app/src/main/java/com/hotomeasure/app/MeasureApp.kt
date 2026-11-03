package com.hotomeasure.app

import android.app.Application
import com.hotomeasure.app.ble.MeterConnection
import com.hotomeasure.app.ble.Scanner
import com.hotomeasure.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MeasureApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: Store
        private set
    lateinit var meter: MeterConnection
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        meter = MeterConnection(this, store, Scanner(this), scope)
    }
}
