package com.cy.xposedshot.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cy.xposedshot.R
import com.cy.xposedshot.config.ConfigStore
import com.cy.xposedshot.config.ModuleConfig
import com.cy.xposedshot.config.RuleBits
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 入口页：六条规则开关、当前放行数量、名单入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var summary: TextView

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyDynamicColor(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(Ui.title(this, getString(R.string.app_name)))
        root.addView(Ui.body(this, getString(R.string.ui_subtitle), 14f).apply {
            setPadding(0, dp(6), 0, 0)
        })

        root.addView(buildRuleCard(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(24), 0, 0) })

        summary = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(20), 0, 0)
            setTextColor(Ui.color(this@MainActivity, com.google.android.material.R.attr.colorPrimary))
        }
        root.addView(summary)

        // 实时同步依赖一次 WRITE_SECURE_SETTINGS 授权；没授权就明说，别让用户以为坏了
        if (!ConfigStore.canWriteSettings(this)) {
            root.addView(Ui.body(this, getString(R.string.ui_permission_needed), 12f).apply {
                setPadding(0, dp(16), 0, 0)
            })
        }

        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = getString(R.string.ui_pick)
            textSize = 16f
            cornerRadius = dp(18)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AppPickerActivity::class.java))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { setMargins(0, dp(18), 0, 0) })

        root.addView(Ui.body(this, getString(R.string.ui_note), 12f).apply {
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        Ui.applySystemInsets(this, root, dp(24))
    }

    private fun buildRuleCard(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(Ui.color(this@MainActivity, com.google.android.material.R.attr.colorOutlineVariant))
            setCardBackgroundColor(Ui.color(this@MainActivity, com.google.android.material.R.attr.colorSurfaceContainerLow))
            setContentPadding(dp(18), dp(10), dp(12), dp(10))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val enabled = ConfigStore.enabledRules(this)
        val items = listOf(
            RuleBits.IME to R.string.rule_item_ime,
            RuleBits.OVERLAY to R.string.rule_item_overlay,
            RuleBits.PIP to R.string.rule_item_pip,
            RuleBits.FREE_WIN to R.string.rule_item_freeform,
            RuleBits.SECURITY to R.string.rule_item_security,
            RuleBits.SCREENSHOT to R.string.rule_item_screenshot,
        )
        items.forEach { (category, titleRes) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(Ui.body(this, getString(titleRes), 15f).apply {
                setTextColor(Ui.color(this@MainActivity, com.google.android.material.R.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialSwitch(this).apply {
                isChecked = enabled and category != 0
                setOnCheckedChangeListener { _, checked ->
                    ConfigStore.setRule(this@MainActivity, category, checked)
                }
            })
            column.addView(row)
        }
        card.addView(column)
        return card
    }

    override fun onResume() {
        super.onResume()
        val count = ModuleConfig.parse(ConfigStore.json(this)).allow.size
        summary.text = if (count == 0) {
            getString(R.string.ui_summary_none)
        } else {
            getString(R.string.ui_summary_count, count)
        }
    }
}
