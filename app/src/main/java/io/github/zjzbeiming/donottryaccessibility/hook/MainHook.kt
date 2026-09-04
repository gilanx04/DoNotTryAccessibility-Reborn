package io.github.zjzbeiming.donottryaccessibility.hook

import android.app.Application
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File

/**
 * 模块入口。声明于 META-INF/xposed/java_init.list。
 *
 * 作用域（scope.list）：
 * - android：核心逻辑，在 system_server 内拦截无障碍服务查询，
 *   让黑名单应用读到"没有开启任何无障碍服务"的伪造结果。
 * - 自身包名：激活自检，注入模块 UI 进程后写标记文件，
 *   供界面显示 Hook 是否已生效。
 */
class MainHook : XposedModule() {

    companion object {
        const val TAG = "DNTA-Reborn"
        const val MODULE_PACKAGE = "io.github.zjzbeiming.donottryaccessibility"
        const val MARKER_FILE = "dnta_active.marker"

        /** system_server 的权威类加载器，首次安装时缓存，供热重载复用（进程级生命周期，无泄漏问题）。 */
        @Volatile
        var systemClassLoader: ClassLoader? = null
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO, TAG,
            "module loaded in ${param.processName}, " +
                "framework: $frameworkName $frameworkVersionCode, api: $apiVersion"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        systemClassLoader = param.classLoader
        log(Log.INFO, TAG, "onSystemServerStarting, installing hooks")
        SystemHook.install(this, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != MODULE_PACKAGE) return
        installActivationCheck()
    }

    /**
     * 激活自检：本进程就是模块 UI 应用，说明 LSPosed 已注入自身作用域。
     * Hook 框架的 Instrumentation.callApplicationOnCreate（公开 API，不依赖
     * 被混淆的本应用类名），在 Application 创建完成后写标记文件。
     * 不 hook com.android.server.*：自身进程不需要业务 Hook。
     */
    private fun installActivationCheck() {
        try {
            val onCreate = Class.forName("android.app.Instrumentation")
                .getMethod("callApplicationOnCreate", Application::class.java)
            hook(onCreate).intercept { chain ->
                val result = chain.proceed()
                try {
                    val app = chain.getArg(0) as? Application
                    if (app != null && app.packageName == MODULE_PACKAGE) {
                        val info = "${System.currentTimeMillis()}|" +
                            "$frameworkName $frameworkVersionCode|api $apiVersion"
                        File(app.filesDir, MARKER_FILE).writeText(info)
                        log(Log.INFO, TAG, "activation marker written: $info")
                    }
                } catch (t: Throwable) {
                    log(Log.WARN, TAG, "failed to write activation marker", t)
                }
                result
            }
            log(Log.INFO, TAG, "activation check installed for module app process")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "failed to install activation check", t)
        }
    }

    /**
     * API 102 热重载：默认实现只卸载旧 Hook，这里在卸载后立即重装。
     * system_server 中热重载不会重放 onSystemServerStarting，
     * 因此复用首次缓存的系统类加载器重新解析 com.android.server.* 类。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        super.onHotReloaded(param)
        log(Log.INFO, TAG, "hot reloaded, reinstalling hooks")
        val cl = systemClassLoader ?: ClassLoader.getSystemClassLoader()
        SystemHook.install(this, cl)
    }
}
