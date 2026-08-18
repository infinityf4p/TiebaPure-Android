package dev.infinityf4p.tiebapure

import android.app.Application

class TiebaPureApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }
}
