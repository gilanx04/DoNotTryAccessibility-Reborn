package io.github.zjzbeiming.donottryaccessibility.hook

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/**
 * 在 system_server 内把调用方 uid 解析为包名集合。
 * 通过 ActivityThread.getSystemContext() 拿到系统 Context，
 * 再走公开 API PackageManager.getPackagesForUid()。
 *
 * 使用 getPackagesForUid 而非 getNameForUid：
 * 共享 uid 场景下后者拿不到真实包名，会漏判。
 */
object UidResolver {

    private const val CACHE_LIMIT = 4096

    private val cache = ConcurrentHashMap<Int, Array<String>>()

    @Volatile
    private var packageManager: PackageManager? = null

    @Volatile
    private var resolved = false

    fun packagesForUid(uid: Int): Array<String>? {
        if (uid <= 0) return null
        cache[uid]?.let { return it }
        if (!resolved) resolve()
        val pm = packageManager ?: return null
        return try {
            val pkgs: Array<String>? = pm.getPackagesForUid(uid)
            if (pkgs != null) {
                if (cache.size > CACHE_LIMIT) cache.clear()
                cache[uid] = pkgs
            }
            pkgs
        } catch (t: Throwable) {
            null
        }
    }

    private fun resolve() {
        synchronized(this) {
            if (resolved) return
            try {
                val at = Class.forName("android.app.ActivityThread")
                val current = at.getMethod("currentActivityThread").invoke(null)
                if (current != null) {
                    val ctx = at.getMethod("getSystemContext").invoke(current) as Context
                    packageManager = ctx.packageManager
                }
            } catch (t: Throwable) {
                // 保持 null，调用侧按 fail-open 处理
            } finally {
                resolved = true
            }
        }
    }
}
