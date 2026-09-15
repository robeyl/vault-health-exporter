package com.vaulthealth.exporter

import android.app.Application

class VaultHealthApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
