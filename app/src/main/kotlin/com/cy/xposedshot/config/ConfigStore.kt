package com.cy.xposedshot.config

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONObject

/**
 * 配置的读写。
 *
 * 主通道是 LSPosed 的 Remote Preferences：模块 App 与 system_server 里的模块共享同一份
 * SharedPreferences，改动由框架 daemon 直接推送（带变更监听），毫秒级生效。
 * 另外把同一份 JSON 写进 `Settings.Global` 与私有 prefs，作为旧框架/未授权时的兜底。
 */
object ConfigStore {

    const val AUTHORITY = "com.cy.xposedshot.config"

    /** 兜底通道：Settings.Global 的键（写入需要 WRITE_SECURE_SETTINGS）。 */
    const val SETTINGS_KEY = "xposedshot_config"

    const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

    /** Remote Preferences 的分组与键名，模块侧必须一致。 */
    const val GROUP = "xposedshot_config"
    const val KEY = "allow_json"

    val URI: Uri = Uri.parse("content://$AUTHORITY/config")

    private const val PREFS = "hide_shot_config"
    private const val KEY_JSON = "allow_json"

    @Volatile
    private var remote: SharedPreferences? = null

    fun remoteBound(): Boolean = remote != null

    fun canWriteSettings(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 绑定框架的 Remote Preferences（LSPosed 1.9+）。 */
    fun install(context: Context) {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    remote = runCatching { service.getRemotePreferences(GROUP) }.getOrNull()
                }

                override fun onServiceDied(service: XposedService) {
                    remote = null
                }
            })
        }
    }

    fun json(context: Context): String =
        remote?.getString(KEY, null)?.takeIf { it.isNotBlank() }
            ?: runCatching { Settings.Global.getString(context.contentResolver, SETTINGS_KEY) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_JSON, null)
                ?: ModuleConfig.emptyJson()

    /** 一次解析拿到整张放行表，列表里按行查询时别再重复解析 JSON。 */
    fun allowMap(context: Context): Map<String, Int> =
        ModuleConfig.parse(json(context)).allow

    fun allowBits(context: Context, pkg: String): Int {
        val root = runCatching { JSONObject(json(context)) }.getOrNull() ?: return 0
        val item = root.optJSONObject("allow")?.optJSONObject(pkg) ?: return 0
        var bits = 0
        if (item.optBoolean("freeWin")) bits = bits or Rules.FREE_WIN
        if (item.optBoolean("overlay")) bits = bits or Rules.OVERLAY
        if (item.optBoolean("pip")) bits = bits or Rules.PIP
        return bits
    }

    fun enabledRules(context: Context): Int =
        ModuleConfig.parse(json(context)).enabled

    /** 按应用放行；[enabled] = 放行。 */
    fun setFlag(context: Context, pkg: String, rule: Int, enabled: Boolean) {
        val root = readRoot(context)
        val allow = root.optJSONObject("allow") ?: JSONObject().also { root.put("allow", it) }
        val item = allow.optJSONObject(pkg) ?: JSONObject().also { allow.put(pkg, it) }
        val key = when (rule) {
            Rules.FREE_WIN -> "freeWin"
            Rules.OVERLAY -> "overlay"
            Rules.PIP -> "pip"
            else -> return
        }
        item.put(key, enabled)
        if (item.length() == 0) allow.remove(pkg)
        writeRoot(context, root)
    }

    /** 打开/关闭某一条全局规则（关掉 = 这一类窗口不再隐藏）。即时生效。 */
    fun setRule(context: Context, category: Int, enabled: Boolean) {
        val root = readRoot(context)
        val rules = root.optJSONObject("rules") ?: ModuleConfig.defaultRules().also { root.put("rules", it) }
        val key = when (category) {
            RuleBits.IME -> "ime"
            RuleBits.OVERLAY -> "overlay"
            RuleBits.PIP -> "pip"
            RuleBits.FREE_WIN -> "freeWin"
            RuleBits.SECURITY -> "security"
            RuleBits.SCREENSHOT -> "screenshot"
            else -> return
        }
        rules.put(key, enabled)
        writeRoot(context, root)
    }

    private fun readRoot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 以当前生效值为基准，避免覆盖掉另一个通道写入的内容
        val source = runCatching { JSONObject(json(context)) }.getOrNull()
            ?: runCatching { JSONObject(prefs.getString(KEY_JSON, null) ?: "{}") }.getOrNull()
            ?: JSONObject()
        if (!source.has("v")) source.put("v", 1)
        return source
    }

    private fun writeRoot(context: Context, root: JSONObject) {
        val text = root.toString()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 主通道：框架 daemon 推给 system_server
        runCatching { remote?.edit()?.putString(KEY, text)?.apply() }
        // 兜底：Settings.Global（需要授权）
        runCatching { Settings.Global.putString(context.contentResolver, SETTINGS_KEY, text) }
        // 离线兜底 + 旧框架
        prefs.edit().putString(KEY_JSON, text).apply()
        runCatching { context.contentResolver.notifyChange(URI, null) }
    }
}
