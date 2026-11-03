package com.hotomeasure.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.hotomeasure.app.ui.MainViewModel
import com.hotomeasure.app.ui.Root
import com.hotomeasure.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root(vm) } }
    }

    // Stay connected only while the app is on screen, like the original app.
    override fun onStart() {
        super.onStart()
        vm.meter.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.meter.setForeground(false, graceMs = 60_000)
    }
}
