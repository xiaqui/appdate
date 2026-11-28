package jp.xiaqui.artifact

import android.app.Application

class AppDateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TlsCompatibility.initialize(this)
    }
}
