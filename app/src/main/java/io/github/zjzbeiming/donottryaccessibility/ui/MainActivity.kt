package io.github.zjzbeiming.donottryaccessibility.ui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import io.github.zjzbeiming.donottryaccessibility.App
import io.github.zjzbeiming.donottryaccessibility.R
import io.github.zjzbeiming.donottryaccessibility.hook.MainHook
import io.github.zjzbeiming.donottryaccessibility.hook.RemoteConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置界面：勾选需要屏蔽的应用（黑名单），写入远程配置。
 * Hook 侧通过 RemotePreferences 监听变更，实时生效（对已注入的 system_server）。
 */
class MainActivity : Activity(), App.ServiceStateListener {

    private class AppRow(
        val label: String,
        val pkg: String,
        val isSystem: Boolean,
        var checked: Boolean
    )

    private val rows = mutableListOf<AppRow>()
    private val shown = mutableListOf<AppRow>()
    private lateinit var adapter: BaseAdapter
    private lateinit var statusView: TextView
    private lateinit var deepHideSwitch: Switch
    private lateinit var showSystemSwitch: Switch
    private lateinit var searchBox: EditText
    private lateinit var listView: ListView
    private var loadingConfig = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyWindowInsets(findViewById(R.id.root))
        statusView = findViewById(R.id.status)
        deepHideSwitch = findViewById(R.id.deep_hide)
        showSystemSwitch = findViewById(R.id.show_system)
        searchBox = findViewById(R.id.search)
        listView = findViewById(R.id.list)

        adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                    ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val row = shown[position]
                view.findViewById<TextView>(R.id.title).text = row.label
                view.findViewById<TextView>(R.id.subtitle).text = row.pkg
                val checkbox = view.findViewById<CheckBox>(R.id.checkbox)
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = row.checked
                checkbox.setOnCheckedChangeListener { _, checked ->
                    row.checked = checked
                    persistBlocked()
                }
                return view
            }
        }
        listView.adapter = adapter

        deepHideSwitch.setOnCheckedChangeListener { _, checked ->
            if (loadingConfig) return@setOnCheckedChangeListener
            val service = App.service ?: return@setOnCheckedChangeListener
            service.getRemotePreferences(RemoteConfig.PREFS_GROUP)
                .edit()
                ?.putBoolean(RemoteConfig.KEY_DEEP_HIDE, checked)
                ?.apply()
        }
        showSystemSwitch.setOnCheckedChangeListener { _, _ -> refilter() }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refilter()
        })

        App.addListener(this, true)
    }

    /**
     * Android 15+ enforces edge-to-edge for this target SDK, so the decor view no
     * longer applies system-bar insets automatically. Apply them to the root
     * container ourselves; otherwise the top rows are drawn under the status bar.
     */
    private fun applyWindowInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.ime()
                )
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            view.setPadding(
                baseLeft + left,
                baseTop + top,
                baseRight + right,
                baseBottom + bottom
            )
            insets
        }
        root.requestApplyInsets()
    }

    override fun onDestroy() {
        App.removeListener(this)
        super.onDestroy()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        runOnUiThread {
            val status = StringBuilder()
            status.append(hookStateLine()).append('\n')
            if (service == null) {
                status.append(getString(R.string.status_no_service))
            } else {
                status.append(
                    getString(
                        R.string.status_service,
                        service.frameworkName,
                        service.frameworkVersion,
                        service.apiVersion
                    )
                )
            }
            statusView.text = status
            if (service != null) {
                loadConfig(service)
                loadApps(service)
                refilter()
            }
        }
    }

    /**
     * 激活自检：模块自身被 LSPosed 注入后，Hook 侧会在 Application 创建完成时
     * 写入标记文件（见 MainHook.installActivationCheck），据此显示激活状态。
     */
    private fun hookStateLine(): String {
        return try {
            val marker = File(filesDir, MainHook.MARKER_FILE)
            if (marker.exists()) {
                val injectedAt = marker.readText().trim()
                    .split('|')
                    .getOrNull(0)
                    ?.toLongOrNull()
                if (injectedAt != null) {
                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(injectedAt))
                    getString(R.string.hook_active, time)
                } else {
                    getString(R.string.hook_active_no_time)
                }
            } else {
                getString(R.string.hook_inactive)
            }
        } catch (t: Throwable) {
            getString(R.string.hook_inactive)
        }
    }

    private fun loadConfig(service: XposedService) {
        loadingConfig = true
        try {
            val prefs = service.getRemotePreferences(RemoteConfig.PREFS_GROUP)
            val blocked = prefs.getString(RemoteConfig.KEY_BLOCKED_PACKAGES, "")
                ?.splitToSequence(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
            for (row in rows) {
                row.checked = row.pkg in blocked
            }
            deepHideSwitch.isChecked = prefs.getBoolean(RemoteConfig.KEY_DEEP_HIDE, false)
        } catch (t: Throwable) {
            Toast.makeText(this, t.toString(), Toast.LENGTH_SHORT).show()
        } finally {
            loadingConfig = false
        }
    }

    private fun loadApps(service: XposedService) {
        val pm = packageManager
        val packages: List<PackageInfo> = try {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        } catch (t: Throwable) {
            emptyList()
        }
        val self = packageName
        rows.clear()
        for (info in packages) {
            val pkg = info.packageName
            if (pkg == self || pkg == "android") continue
            val appInfo: ApplicationInfo = info.applicationInfo ?: continue
            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (t: Throwable) {
                pkg
            }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            rows.add(AppRow(label, pkg, isSystem, checked = false))
        }
        rows.sortWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    private fun refilter() {
        val query = searchBox.text.toString().trim().lowercase()
        val withSystem = showSystemSwitch.isChecked
        shown.clear()
        for (row in rows) {
            if (!withSystem && row.isSystem) continue
            if (query.isNotEmpty() &&
                !row.label.lowercase().contains(query) &&
                !row.pkg.lowercase().contains(query)
            ) continue
            shown.add(row)
        }
        adapter.notifyDataSetChanged()
    }

    private fun persistBlocked() {
        val service = App.service ?: return
        val joined = rows.filter { it.checked }.joinToString(",") { it.pkg }
        try {
            service.getRemotePreferences(RemoteConfig.PREFS_GROUP)
                .edit()
                ?.putString(RemoteConfig.KEY_BLOCKED_PACKAGES, joined)
                ?.apply()
        } catch (t: Throwable) {
            Toast.makeText(this, t.toString(), Toast.LENGTH_SHORT).show()
        }
    }
}
