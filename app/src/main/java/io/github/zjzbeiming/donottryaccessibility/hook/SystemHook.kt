package io.github.zjzbeiming.donottryaccessibility.hook

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ContentProvider
import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.Collections

/**
 * Installs all system_server hooks.
 *
 * Covered entry points:
 * 1. AccessibilityManagerService.addClient
 *    A blocked app gets a zero client state and is not registered for updates.
 * 2. AccessibilityManagerService.getEnabledAccessibilityServiceList
 *    A blocked app gets an empty list.
 * 3. IAccessibilityManager binder transactions 3 (addClient) and
 *    6 (getEnabledAccessibilityServiceList). This is the reliable fallback on
 *    Android 17, where ART may not invoke the service-level hooks.
 * 4. AccessibilityManagerService.getInstalledAccessibilityServiceList
 *    Optional deep hiding: a blocked app sees no installed services either.
 * 5. SettingsProvider.call
 *    A blocked app gets empty/zero values for the two accessibility secure
 *    settings while the real settings stay intact for everyone else.
 */
object SystemHook {

    private const val GET_SECURE = "GET_secure"
    private const val GENERATION_INDEX_KEY = "_generation_index"
    private const val SETTINGS_PROVIDER_PACKAGE = "com.android.providers.settings"
    private const val SETTINGS_PROVIDER_CLASS = "com.android.providers.settings.SettingsProvider"

    @Volatile
    private var settingsProviderHooked = false

    fun install(xposed: XposedInterface, classLoader: ClassLoader) {
        RemoteConfig.init(xposed)
        settingsProviderHooked = false

        val servicesOk = hookAccessibilityManagerService(xposed, classLoader)
        val binderOk = hookAccessibilityManagerTransactions(xposed, classLoader)
        val settingsOk = hookSettingsProviderClass(xposed, classLoader)

        xposed.log(
            Log.INFO,
            MainHook.TAG,
            "system hooks installed: services=$servicesOk, binder=$binderOk, " +
                "settings=$settingsOk"
        )
    }

    // ------------------------------------------------------------- services

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
        for (method in ams.declaredMethods) {
            when {
                method.name == "addClient" -> {
                    hookSafely(xposed, method) { chain ->
                        if (RemoteConfig.isBlocked(Binder.getCallingUid())) {
                            zeroFor((chain.executable as Method).returnType)
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }

                method.name == "getEnabledAccessibilityServiceList" &&
                    List::class.java.isAssignableFrom(method.returnType) -> {
                    hookSafely(xposed, method) { chain ->
                        if (RemoteConfig.isBlocked(Binder.getCallingUid())) {
                            Collections.emptyList<Any>()
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }

                method.name == "getInstalledAccessibilityServiceList" -> {
                    hookSafely(xposed, method) { chain ->
                        val uid = Binder.getCallingUid()
                        if (RemoteConfig.isDeepHide() && RemoteConfig.isBlocked(uid)) {
                            emptySlice((chain.executable as Method).returnType)
                        } else {
                            chain.proceed()
                        }
                    }
                    hooked++
                }
            }
        }
        return hooked > 0
    }

    /**
     * Binder-level fallback for the two service calls that ART may inline on
     * Android 17. Transaction codes from IAccessibilityManager:
     * 3 = addClient, 5 = getInstalledAccessibilityServiceList,
     * 6 = getEnabledAccessibilityServiceList.
     */
    private fun hookAccessibilityManagerTransactions(
        xposed: XposedInterface,
        classLoader: ClassLoader
    ): Boolean {
        val stub = try {
            classLoader.loadClass("android.view.accessibility.IAccessibilityManager\$Stub")
        } catch (t: Throwable) {
            xposed.log(Log.ERROR, MainHook.TAG, "IAccessibilityManager\$Stub not found", t)
            return false
        }

        var hooked = 0
        for (method in stub.declaredMethods) {
            if (method.name != "onTransact") continue
            hookSafely(xposed, method) { chain ->
                val code = chain.getArg(0) as? Int ?: -1
                if (code != 3 && code != 5 && code != 6) {
                    chain.proceed()
                } else {
                    val blocked = RemoteConfig.isBlocked(Binder.getCallingUid())
                    when {
                        !blocked -> chain.proceed()

                        code == 3 -> {
                            // Do not register the client; report zero state.
                            val reply = chain.getArg(2) as? android.os.Parcel
                            if (reply == null) {
                                chain.proceed()
                            } else {
                                reply.setDataPosition(0)
                                reply.writeNoException()
                                reply.writeLong(0L)
                                true
                            }
                        }

                        code == 6 -> {
                            // Return an empty enabled-service list.
                            val reply = chain.getArg(2) as? android.os.Parcel
                            if (reply == null) {
                                chain.proceed()
                            } else {
                                reply.setDataPosition(0)
                                reply.writeNoException()
                                reply.writeTypedList(
                                    Collections.emptyList<AccessibilityServiceInfo>(),
                                    1
                                )
                                true
                            }
                        }

                        // getInstalled is handled by the service hook.
                        else -> chain.proceed()
                    }
                }
            }
            hooked++
        }
        return hooked > 0
    }

    /** addClient returns int before Android 12 and long from Android 12 on. */
    private fun zeroFor(type: Class<*>): Any = when (type) {
        java.lang.Long.TYPE -> 0L
        java.lang.Integer.TYPE -> 0
        java.lang.Boolean.TYPE -> false
        else -> 0L
    }

    private fun emptySlice(returnType: Class<*>): Any = try {
        val slice = Class.forName("android.content.pm.ParceledListSlice")
        if (returnType == slice) {
            slice.getMethod("emptyList").invoke(null)
        } else {
            Collections.emptyList<Any>()
        }
    } catch (_: Throwable) {
        Collections.emptyList<Any>()
    }

    // ------------------------------------------------------------- settings

    /**
     * SettingsProvider is loaded from its own APK by a child classloader, not
     * by the classloader used for system_server. Hook the live provider class
     * through ActivityThread so the hook lands on the copy that actually runs.
     */
    private fun hookSettingsProviderClass(
        xposed: XposedInterface,
        classLoader: ClassLoader
    ): Boolean {
        val direct = findSettingsProviderClass(classLoader)
        if (direct != null && hookSettingsProviderMethods(xposed, direct)) {
            return true
        }

        // SettingsProvider may still be installing while the module starts.
        Thread {
            for (attempt in 0 until 120) {
                if (settingsProviderHooked) return@Thread
                try {
                    Thread.sleep(250)
                    val provider = findSettingsProviderClass(classLoader)
                    if (provider != null &&
                        hookSettingsProviderMethods(xposed, provider)
                    ) {
                        return@Thread
                    }
                } catch (_: Throwable) {
                    // Retry until the provider appears.
                }
            }
            xposed.log(
                Log.WARN,
                MainHook.TAG,
                "SettingsProvider not available; accessibility settings may remain visible"
            )
        }.start()
        return false
    }

    private fun findSettingsProviderClass(classLoader: ClassLoader): Class<*>? {
        return try {
            val activityThreadClass = classLoader.loadClass("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null)

            if (activityThread != null) {
                for (fieldName in listOf("mLocalProviders", "mProviderMap")) {
                    val field = activityThreadClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val map = field.get(activityThread) as? Map<*, *> ?: continue
                    for (value in map.values) {
                        val provider = fieldValue(value, "mLocalProvider") as? ContentProvider
                        if (provider != null &&
                            provider.javaClass.name == SETTINGS_PROVIDER_CLASS
                        ) {
                            return provider.javaClass
                        }
                    }
                }

                // Fallback: ask LoadedApk for the provider package classloader.
                val packagesField = activityThreadClass.getDeclaredField("mPackages")
                packagesField.isAccessible = true
                val packages = packagesField.get(activityThread) as? Map<*, *>
                if (packages != null) {
                    val reference = packages[SETTINGS_PROVIDER_PACKAGE]
                    if (reference != null) {
                        val loadedApk = try {
                            reference.javaClass.getMethod("get").invoke(reference)
                        } catch (_: Throwable) {
                            reference
                        }
                        if (loadedApk != null) {
                            val providerClassLoader = loadedApk.javaClass
                                .getMethod("getClassLoader")
                                .invoke(loadedApk) as? ClassLoader
                            if (providerClassLoader != null) {
                                return providerClassLoader.loadClass(SETTINGS_PROVIDER_CLASS)
                            }
                        }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookSettingsProviderMethods(
        xposed: XposedInterface,
        provider: Class<*>
    ): Boolean {
        if (settingsProviderHooked) return true

        var hooked = 0
        for (method in provider.declaredMethods) {
            if (method.name != "call") continue
            val parameterTypes = method.parameterTypes
            if (parameterTypes.size < 3 ||
                parameterTypes[parameterTypes.size - 1] != Bundle::class.java
            ) {
                continue
            }

            hookSafely(xposed, method) { chain ->
                // Capture before the provider can clear/restore calling identity.
                val uid = Binder.getCallingUid()
                val result = chain.proceed()
                sanitizeSettingsBundle(uid, chain.args, result)
                result
            }
            hooked++
        }

        settingsProviderHooked = hooked > 0
        return settingsProviderHooked
    }

    private fun fieldValue(target: Any?, name: String): Any? = try {
        if (target == null) {
            null
        } else {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(target)
        }
    } catch (_: Throwable) {
        null
    }

    private fun sanitizeSettingsBundle(
        uid: Int,
        args: List<Any?>,
        result: Any?
    ) {
        if (result !is Bundle || !RemoteConfig.isBlocked(uid)) return

        var methodIndex = -1
        var authorityIndex = -1
        for (i in args.indices) {
            when (args[i]) {
                GET_SECURE -> {
                    methodIndex = i
                    break
                }

                Settings.AUTHORITY -> authorityIndex = i
            }
        }
        if (methodIndex < 0 || methodIndex + 1 >= args.size) return
        // For ContentProvider.Transport.call the authority precedes the method.
        // SettingsProvider.call has no authority argument, so it is only
        // required when present.
        if (authorityIndex >= 0 && authorityIndex > methodIndex) return

        val key = args[methodIndex + 1] as? String ?: return
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

    // ---------------------------------------------------------------- utils

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
