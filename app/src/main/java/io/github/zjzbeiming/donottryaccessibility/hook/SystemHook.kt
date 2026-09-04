package io.github.zjzbeiming.donottryaccessibility.hook

import android.content.ContentProvider
import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections

/**
 * 系统级 Hook 安装器，全部逻辑只在 system_server 内执行。
 *
 * 拦截点（对齐原版 DoNotTryAccessibility 并修复其中问题）：
 * 1. AccessibilityManagerService.addClient
 *      黑名单应用注册无障碍客户端时直接返回 0（等价于"无任何已启用服务"）。
 * 2. AccessibilityManagerService.getEnabledAccessibilityServiceList
 *      黑名单应用查询已启用服务列表时返回空列表。
 * 3. AccessibilityManagerService.getInstalledAccessibilityServiceList
 *      深度隐藏（可选，原版中注释未启用）：连已安装的无障碍服务也返回空。
 * 4. ContentProvider$Transport.call
 *      黑名单应用通过 Settings.Secure 读取
 *      ENABLED_ACCESSIBILITY_SERVICES / ACCESSIBILITY_ENABLED 时改写返回值。
 *
 * 修复的原版问题：
 * - 原版 call 拦截里 `args(1) != AUTHORITY && args(2) != "GET_secure"`
 *   用了 && 短路，authority 匹配但 method 不匹配时会漏放行；本版改为
 *   按参数值定位 authority/method/key 的通用匹配，不依赖固定下标，
 *   同时兼容新旧两种 call 签名。
 * - 原版按 getNameForUid 判断包名，共享 uid 会漏判；本版改用
 *   getPackagesForUid。
 */
object SystemHook {

    private const val GET_SECURE = "GET_secure"
    private const val GENERATION_INDEX_KEY = "_generation_index"

    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        RemoteConfig.init(xposed)
        val amsOk = hookAccessibilityManagerService(xposed, classLoader)
        val providerOk = hookSettingsProvider(xposed)
        xposed.log(
            Log.INFO, MainHook.TAG,
            "install finished: ams=$amsOk, settingsProvider=$providerOk"
        )
    }

    // ---------------------------------------------------------------- AMS

    private fun hookAccessibilityManagerService(
        xposed: XposedInterface,
        classLoader: ClassLoader
    ): Boolean {
        val ams = try {
            classLoader.loadClass("com.android.server.accessibility.AccessibilityManagerService")
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, MainHook.TAG, "AccessibilityManagerService not found", t)
            return false
        }
        var hooked = 0
        for (m in ams.declaredMethods) {
            // 按方法名匹配（AOSP main 2026-09 核对：三个方法均只有唯一同名重载），
            // 不绑定参数个数，避免未来 Android 签名漂移导致 Hook 静默失效；
            // 返回值在运行时按实际 returnType 决定。
            when {
                m.name == "addClient" -> {
                    hookSafely(xposed, m) { chain ->
                        if (RemoteConfig.isBlocked(Binder.getCallingUid())) {
                            zeroFor((chain.executable as Method).returnType)
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }

                m.name == "getEnabledAccessibilityServiceList" &&
                    List::class.java.isAssignableFrom(m.returnType) -> {
                    hookSafely(xposed, m) { chain ->
                        if (RemoteConfig.isBlocked(Binder.getCallingUid())) {
                            Collections.emptyList<Any>()
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }

                m.name == "getInstalledAccessibilityServiceList" -> {
                    hookSafely(xposed, m) { chain ->
                        if (RemoteConfig.isDeepHide() &&
                            RemoteConfig.isBlocked(Binder.getCallingUid())
                        ) {
                            emptySlice((chain.executable as Method).returnType)
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }
            }
        }
        xposed.log(Log.INFO, MainHook.TAG, "hooked $hooked AccessibilityManagerService methods")
        return hooked > 0
    }

    /** addClient 在 Android 11 及以下返回 int、12+ 返回 long，按返回类型给零值。 */
    private fun zeroFor(type: Class<*>): Any = when (type) {
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        java.lang.Boolean.TYPE -> false
        else -> 0L
    }

    /** 深度隐藏时返回空的 ParceledListSlice。 */
    private fun emptySlice(returnType: Class<*>): Any = try {
        val pls = Class.forName("android.content.pm.ParceledListSlice")
        if (returnType == pls) {
            pls.getMethod("emptyList").invoke(null)
        } else {
            Collections.emptyList<Any>()
        }
    } catch (t: Throwable) {
        Collections.emptyList<Any>()
    }

    // ------------------------------------------------- Settings Provider

    private fun hookSettingsProvider(xposed: XposedInterface): Boolean {
        val transport = try {
            Class.forName("android.content.ContentProvider\$Transport")
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, MainHook.TAG, "ContentProvider\$Transport not found", t)
            return false
        }
        var hooked = 0
        for (m in transport.declaredMethods) {
            if (m.name != "call") continue
            val paramTypes = m.parameterTypes
            // 兼容两种签名：
            // 新: call(AttributionSource, String authority, String method, String arg, Bundle extras)
            // 旧: call(String authority, String method, String arg, Bundle extras)
            if (paramTypes.size < 4 || paramTypes[paramTypes.size - 1] != Bundle::class.java) continue
            hookSafely(xposed, m) { chain ->
                val result = chain.proceed()
                sanitizeSettingsResult(chain, result)
                result
            }
            hooked++
        }
        xposed.log(Log.INFO, MainHook.TAG, "hooked $hooked Transport.call overloads")
        return hooked > 0
    }

    /**
     * 通用匹配：在参数列表里按值定位 authority / method / key，
     * 不依赖固定下标。要求 authority 出现在 method 之前，避免误匹配。
     */
    private fun sanitizeSettingsResult(chain: XposedInterface.Chain, result: Any?) {
        if (result !is Bundle) return
        val args = chain.args
        var authorityIndex = -1
        var methodIndex = -1
        var keyIndex = -1
        for (i in args.indices) {
            when (args[i]) {
                Settings.AUTHORITY -> authorityIndex = i
                GET_SECURE -> {
                    methodIndex = i
                    keyIndex = i + 1
                }
            }
        }
        if (methodIndex < 0 || authorityIndex < 0 || authorityIndex > methodIndex) return
        if (keyIndex >= args.size || keyIndex <= methodIndex) return
        val key = args[keyIndex] as? String ?: return
        if (!RemoteConfig.isBlocked(Binder.getCallingUid())) return
        when (key) {
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES -> {
                result.putString(Settings.NameValueTable.VALUE, "")
                result.putInt(GENERATION_INDEX_KEY, -1)
            }

            Settings.Secure.ACCESSIBILITY_ENABLED -> {
                result.putString(Settings.NameValueTable.VALUE, "0")
                result.putInt(GENERATION_INDEX_KEY, -1)
            }
        }
    }

    // ------------------------------------------------------------ utils

    private fun hookSafely(
        xposed: XposedInterface,
        method: Method,
        interceptor: (XposedInterface.Chain) -> Any?
    ) {
        try {
            xposed.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> interceptor(chain) }
        } catch (t: Throwable) {
            xposed.log(Log.WARN, MainHook.TAG, "hook failed: ${method.name}", t)
        }
    }
}
