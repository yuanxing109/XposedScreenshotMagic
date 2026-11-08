package com.cy.xposedshot.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cy.xposedshot.R
import com.cy.xposedshot.config.ConfigStore
import com.cy.xposedshot.config.Rules
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 应用名单。列表只显示「这个 App 放行了哪几类」，点进去在底部弹窗里逐项开关。
 */
class AppPickerActivity : AppCompatActivity() {

    private class Entry(
        val label: String,
        val pkg: String,
        val system: Boolean,
    )

    private sealed class Row {
        class Header(val title: String) : Row()
        class App(val entry: Entry) : Row()
    }

    private val all = ArrayList<Entry>()
    private val rows = ArrayList<Row>()
    private var userCount = 0
    private var systemCount = 0
    private val iconCache = HashMap<String, Drawable?>()

    private var showSystem = false
    private var keyword = ""

    /** 当前配置的放行表，列表分组与行内状态都读它。 */
    private var allowCache: Map<String, Int> = emptyMap()

    private fun bitsOf(pkg: String): Int = allowCache[pkg] ?: 0

    private lateinit var adapter: RowAdapter
    private lateinit var list: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int): Int = (value * density).toInt()
    private fun color(attr: Int): Int = Ui.color(this, attr)

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.applyDynamicColor(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val toolbar = Toolbar(this).apply {
            title = getString(R.string.picker_title)
            setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
            navigationIcon?.setTint(color(com.google.android.material.R.attr.colorOnSurface))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ))

        root.addView(buildSearchBar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        root.addView(buildSystemRow())
        root.addView(buildListArea(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        Ui.applySystemInsets(this, root, 0)
        loadApps()
    }

    // ---- 顶部控件 ----

    private fun buildSearchBar(): View {
        val bar = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = android.content.res.ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })

        val input = EditText(this).apply {
            hint = getString(R.string.picker_search)
            textSize = 15f
            background = null
            setSingleLine(true)
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setHintTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(row)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        return bar
    }

    private fun buildSystemRow(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(2), dp(20), 0)
        }
        row.addView(Ui.body(this, getString(R.string.picker_show_system), 14f).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialSwitch(this).apply {
            isChecked = showSystem
            setOnCheckedChangeListener { _, checked ->
                showSystem = checked
                applyFilter()
                if (checked) list.scrollToPosition(0)
            }
        })
        column.addView(row)
        countView = Ui.body(this, "", 12f).apply {
            setPadding(dp(20), 0, dp(20), dp(6))
        }
        column.addView(countView)
        return column
    }

    private fun buildListArea(): View {
        val area = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            adapter = RowAdapter().also { this@AppPickerActivity.adapter = it }
            setClipToPadding(false)
            setPadding(0, dp(4), 0, dp(16))
        }
        emptyView = Ui.body(this, getString(R.string.picker_empty), 14f).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        area.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        area.addView(emptyView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return area
    }

    // ---- 数据 ----

    private fun loadApps() {
        Thread {
            // 不用 getInstalledApplications：HyperOS 会以「Permission Denied」直接拦掉，
            // 即使 manifest 里声明了 QUERY_ALL_PACKAGES。带桌面入口的查询不受影响。
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching { pm.queryIntentActivities(launcherIntent, 0) }
                .getOrDefault(emptyList())
            val result = LinkedHashMap<String, Entry>()
            for (info in resolved) {
                val appInfo = info.activityInfo?.applicationInfo ?: continue
                val pkg = appInfo.packageName
                if (pkg == packageName || result.containsKey(pkg)) continue
                // MIUI 的预装应用大多落在 /data/app、不带 FLAG_SYSTEM（笔记、查找设备都是），
                // 只看标志会把它们当成用户应用。但「没有安装来源」不能用：侧载 / 文件管理器装的
                // 第三方包也是 null，会把用户自己的应用藏起来。所以只认「系统标志」+「预装包名空间」。
                val system = appInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
                    PRELOAD_PREFIXES.any { pkg.startsWith(it) }
                result[pkg] = Entry(
                    label = runCatching { appInfo.loadLabel(pm).toString() }.getOrDefault(pkg),
                    pkg = pkg,
                    system = system,
                )
            }
            // 中文名要按拼音排，默认的字符码点排序会把中文全排到英文后面
            val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
            val sorted = ArrayList(result.values).sortedWith { a, b ->
                val byLabel = collator.compare(a.label, b.label)
                if (byLabel != 0) byLabel else a.pkg.compareTo(b.pkg)
            }
            runOnUiThread {
                all.clear()
                all.addAll(sorted)
                applyFilter()
            }
        }.start()
    }

    private fun applyFilter() {
        val needle = keyword.lowercase()
        allowCache = ConfigStore.allowMap(this)

        val users = ArrayList<Entry>()
        val systems = ArrayList<Entry>()
        val allowed = ArrayList<Entry>()
        for (entry in all) {
            if (needle.isNotEmpty() &&
                !entry.label.lowercase().contains(needle) &&
                !entry.pkg.lowercase().contains(needle)
            ) continue
            if (entry.system) systems.add(entry) else users.add(entry)
            if (bitsOf(entry.pkg) != 0) allowed.add(entry)
        }
        userCount = users.size
        systemCount = systems.size

        val systemRest = systems.filter { bitsOf(it.pkg) == 0 }
        val userRest = users.filter { bitsOf(it.pkg) == 0 }

        rows.clear()
        // 已放行的应用永远排最前，不用每次往下翻
        if (allowed.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_allowed)))
            allowed.forEach { rows.add(Row.App(it)) }
        }
        if (showSystem && systemRest.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_system)))
            systemRest.forEach { rows.add(Row.App(it)) }
        }
        if (userRest.isNotEmpty()) {
            if (showSystem) rows.add(Row.Header(getString(R.string.section_user)))
            userRest.forEach { rows.add(Row.App(it)) }
        }
        adapter.notifyDataSetChanged()

        val isEmpty = rows.isEmpty()
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        countView.text = if (showSystem) {
            getString(R.string.picker_count_all, userCount + systemCount, systemCount)
        } else {
            getString(R.string.picker_count_user, userCount)
        }
    }

    // ---- 放行详情弹窗 ----

    private fun showSheet(entry: Entry) {
        val sheet = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageDrawable(iconFor(entry.pkg))
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        nameCol.addView(TextView(this).apply {
            text = entry.label
            textSize = 18f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        })
        nameCol.addView(Ui.body(this, entry.pkg, 12f))
        header.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header)

        content.addView(Ui.body(this, getString(R.string.sheet_hint), 12f).apply {
            setPadding(0, dp(14), 0, dp(4))
        })

        val rules = intArrayOf(Rules.FREE_WIN, Rules.OVERLAY, Rules.PIP)
        val titles = intArrayOf(R.string.rule_free_win, R.string.rule_overlay, R.string.rule_pip)
        val descs = intArrayOf(R.string.rule_free_win_desc, R.string.rule_overlay_desc, R.string.rule_pip_desc)
        var bits = ConfigStore.allowBits(this, entry.pkg)
        rules.forEachIndexed { index, rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(this).apply {
                text = getString(titles[index])
                textSize = 16f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            })
            textCol.addView(Ui.body(this, getString(descs[index]), 12f).apply {
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val toggle = MaterialSwitch(this).apply {
                isChecked = bits and rule != 0
                setOnCheckedChangeListener { _, checked ->
                    ConfigStore.setFlag(this@AppPickerActivity, entry.pkg, rule, checked)
                    bits = ConfigStore.allowBits(this@AppPickerActivity, entry.pkg)
                }
            }
            row.addView(toggle)
            content.addView(row)
        }

        sheet.setContentView(content)
        sheet.setOnDismissListener { applyFilter() }
        sheet.show()
    }

    /** 列表右侧的状态摘要：列出已放行的类别。 */
    private fun summaryOf(bits: Int): String {
        val parts = ArrayList<String>(3)
        if (bits and Rules.FREE_WIN != 0) parts.add(getString(R.string.rule_free_win))
        if (bits and Rules.OVERLAY != 0) parts.add(getString(R.string.rule_overlay))
        if (bits and Rules.PIP != 0) parts.add(getString(R.string.rule_pip))
        return if (parts.isEmpty()) getString(R.string.row_status_default) else parts.joinToString(" · ")
    }

    // ---- 列表 ----

    private inner class RowHolder(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val icon: ImageView
        val label: TextView
        val pkg: TextView
        val status: TextView
        val chevron: ImageView

        init {
            val row = LinearLayout(card.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            icon = ImageView(card.context)
            row.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))

            val textCol = LinearLayout(card.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            label = TextView(card.context).apply {
                textSize = 16f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            }
            pkg = TextView(card.context).apply {
                textSize = 12f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            status = TextView(card.context).apply {
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
                setTextColor(color(com.google.android.material.R.attr.colorPrimary))
            }
            textCol.addView(label)
            textCol.addView(pkg)
            textCol.addView(status)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            chevron = ImageView(card.context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    color(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
            }
            row.addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
            card.addView(row)
        }
    }

    private inner class HeaderHolder(text: TextView) : RecyclerView.ViewHolder(text)

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) TYPE_HEADER else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_HEADER) {
                val label = Ui.body(this@AppPickerActivity, "", 12f).apply {
                    setPadding(dp(24), dp(14), dp(24), dp(6))
                }
                return HeaderHolder(label)
            }
            val card = MaterialCardView(parent.context).apply {
                radius = dp(20).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant))
                setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow))
                setContentPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(16), dp(5), dp(16), dp(5)) }
            }
            return RowHolder(card)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (row is Row.Header) {
                (holder.itemView as TextView).text = row.title
                return
            }
            bindApp(holder as RowHolder, (row as Row.App).entry)
        }

        private fun bindApp(holder: RowHolder, entry: Entry) {
            holder.label.text = entry.label
            holder.pkg.text = entry.pkg
            holder.icon.setImageDrawable(iconFor(entry.pkg))
            val bits = bitsOf(entry.pkg)
            holder.status.text = summaryOf(bits)
            holder.status.setTextColor(
                if (bits == 0) {
                    color(com.google.android.material.R.attr.colorOnSurfaceVariant)
                } else {
                    color(com.google.android.material.R.attr.colorPrimary)
                }
            )
            holder.card.setOnClickListener { showSheet(entry) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_APP = 1

        /** 预装应用的包名空间；HyperOS 上这些基本都不是用户自己装的。 */
        val PRELOAD_PREFIXES = listOf(
            "com.miui.", "com.xiaomi.", "miui.", "com.mi.", "com.android.", "com.google.",
        )
    }

    private fun iconFor(pkg: String): Drawable? {
        if (iconCache.containsKey(pkg)) return iconCache[pkg]
        val drawable = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
        iconCache[pkg] = drawable
        return drawable
    }
}
