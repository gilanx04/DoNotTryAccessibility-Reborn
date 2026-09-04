package io.github.zjzbeiming.donottryaccessibility

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.Volatile

/**
 * 模块 UI 侧入口：绑定 LSPosed 服务（io.github.libxposed:service），
 * 用绑定的 XposedService 读写远程配置（RemotePreferences）。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        fun addListener(listener: ServiceStateListener, notifyNow: Boolean) {
            listeners.add(listener)
            if (notifyNow) {
                listener.onServiceStateChanged(service)
            }
        }

        fun removeListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        App.service = service
        for (l in listeners) {
            l.onServiceStateChanged(service)
        }
    }

    override fun onServiceDied(service: XposedService) {
        App.service = null
        for (l in listeners) {
            l.onServiceStateChanged(null)
        }
    }
}
