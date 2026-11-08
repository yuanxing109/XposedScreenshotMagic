package com.cy.xposedshot.config

import org.json.JSONObject

/**
 * 按应用放行的规则位掩码。语义统一为「**放行**」：
 * 某包某位为 true 表示这类窗口**不隐藏**，保持原样出现在截图里。
 */
object Rules {
    const val FREE_WIN = 1   // 悬浮小窗（windowingMode = FREEFORM）
    const val OVERLAY = 2    // 应用悬浮窗（type = 2038）
    const val PIP = 4        // 画中画（2038 + Task windowingMode = PINNED）
}

/** 六条隐藏规则各自的开关位；全开是 [ALL]。 */
object RuleBits {
    const val IME = 1
    const val OVERLAY = 2
    const val PIP = 4
    const val FREE_WIN = 8
    const val SECURITY = 16
    const val SCREENSHOT = 32
    const val ALL = IME or OVERLAY or PIP or FREE_WIN or SECURITY or SCREENSHOT
}

/**
 * 模块配置。
 *
 * JSON 形态（Provider / Settings / Remote Preferences 共用同一份文本）：
 * ```json
 * {"v":1,
 *  "rules":{"ime":true,"overlay":true,"pip":true,"freeWin":true,"security":true,"screenshot":true},
 *  "allow":{"com.quark.browser":{"freeWin":true,"overlay":false,"pip":true}}}
 * ```
 * `rules` 是全局开关（缺省全开），`allow` 是按应用的放行位；两处都缺省即全部隐藏。
 */
class ModuleConfig(val allow: Map<String, Int>, val enabled: Int) {

    fun allowed(pkg: String, rule: Int): Boolean {
        val bits = allow[pkg] ?: return false
        return bits and rule != 0
    }

    fun isEnabled(category: Int): Boolean = enabled and category != 0

    override fun equals(other: Any?): Boolean =
        other is ModuleConfig && other.allow == allow && other.enabled == enabled

    override fun hashCode(): Int = allow.hashCode() * 31 + enabled

    companion object {
        val EMPTY = ModuleConfig(emptyMap(), RuleBits.ALL)

        fun parse(json: String?): ModuleConfig {
            if (json.isNullOrBlank()) return EMPTY
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return EMPTY

            var enabled = 0
            val rules = root.optJSONObject("rules")
            if (rules == null) {
                enabled = RuleBits.ALL
            } else {
                if (rules.optBoolean("ime", true)) enabled = enabled or RuleBits.IME
                if (rules.optBoolean("overlay", true)) enabled = enabled or RuleBits.OVERLAY
                if (rules.optBoolean("pip", true)) enabled = enabled or RuleBits.PIP
                if (rules.optBoolean("freeWin", true)) enabled = enabled or RuleBits.FREE_WIN
                if (rules.optBoolean("security", true)) enabled = enabled or RuleBits.SECURITY
                if (rules.optBoolean("screenshot", true)) enabled = enabled or RuleBits.SCREENSHOT
            }

            val apps = root.optJSONObject("allow")
            val allow = HashMap<String, Int>(apps?.length() ?: 0)
            if (apps != null) {
                for (pkg in apps.keys()) {
                    val item = apps.optJSONObject(pkg) ?: continue
                    var bits = 0
                    if (item.optBoolean("freeWin")) bits = bits or Rules.FREE_WIN
                    if (item.optBoolean("overlay")) bits = bits or Rules.OVERLAY
                    if (item.optBoolean("pip")) bits = bits or Rules.PIP
                    if (bits != 0) allow[pkg] = bits
                }
            }
            return ModuleConfig(allow, enabled)
        }

        fun emptyJson(): String = JSONObject().put("v", 1)
            .put("rules", defaultRules())
            .put("allow", JSONObject())
            .toString()

        fun defaultRules(): JSONObject = JSONObject()
            .put("ime", true)
            .put("overlay", true)
            .put("pip", true)
            .put("freeWin", true)
            .put("security", true)
            .put("screenshot", true)
    }
}
