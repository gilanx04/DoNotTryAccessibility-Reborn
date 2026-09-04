package io.github.zjzbeiming.donottryaccessibility.hook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Hook 侧配置读取。数据来自 LSPosed 远程配置（RemotePreferences），
 * 由模块 UI 通过 io.github.libxposed:service 写入，本侧只读。
 *
 * 读取失败或未配置时一律按"不拦截"处理（fail-open），
 * 优先保证系统功能不被破坏。
 */
object RemoteConfig {

    const val PREFS_GROUP = "config"
    const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    const val KEY_DEEP_HIDE = "deep_hide"

    @Volatile
    private var blocked: Set<String> = emptySet()

    @Volatile
    private var deepHide: Boolean = false

    private lateinit var prefs: SharedPreferences

    /** 强引用持有，防止 SharedPreferences 的弱引用监听器被回收。 */
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> reload(p) }

    fun init(xposed: XposedInterface) {
        if (this::prefs.isInitialized) return
        prefs = try {
            xposed.getRemotePreferences(PREFS_GROUP)
        } catch (t: Throwable) {
            xposed.log(android.util.Log.WARN, MainHook.TAG, "remote preferences unavailable", t)
            return
        }
        reload(prefs)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun reload(p: SharedPreferences) {
        blocked = p.getString(KEY_BLOCKED_PACKAGES, "")
            ?.splitToSequence(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        deepHide = p.getBoolean(KEY_DEEP_HIDE, false)
    }

    /** 当前调用方（按 uid 解析出的包名集合）是否在黑名单内。 */
    fun isBlocked(uid: Int): Boolean {
        if (blocked.isEmpty()) return false
        val pkgs = UidResolver.packagesForUid(uid) ?: return false
        for (pkg in pkgs) {
            if (pkg in blocked) return true
        }
        return false
    }

    fun isDeepHide(): Boolean = deepHide
}
