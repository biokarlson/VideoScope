package com.example.videoscope.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.example.videoscope.gl.GLRenderer
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Панель настроек справа от живой картинки, три вкладки: Видео, Аудио, Интерфейс.
 * Собрана кодом, без XML и сторонних библиотек.
 */
class MenuPanel(context: Context, private val host: Host) : LinearLayout(context) {

    interface Host {
        fun getPalette(): Int
        fun setPalette(p: Int)
        fun getMonoHue(): Float
        fun setMonoHue(h: Float)
        fun getMonoSat(): Float
        fun setMonoSat(s: Float)
        fun getEffectOn(index: Int): Boolean          // 0 = VHS, 1 = Glitch, 2 = Noise
        fun setEffectOn(index: Int, on: Boolean)
        fun getEffectStrength(index: Int): Float
        fun setEffectStrength(index: Int, v: Float)
        fun getOverall(): Float
        fun setOverall(v: Float)
        fun getGainStep(): Float
        fun setGainStep(v: Float)
        fun getLevel(): Float
        fun getPeak(): Float
        fun isOsdOn(): Boolean
        fun setOsdOn(v: Boolean)
        fun isExitGuard(): Boolean
        fun setExitGuard(v: Boolean)
        fun isPauseEnabled(): Boolean
        fun setPauseEnabled(v: Boolean)
        fun getLastTab(): Int
        fun setLastTab(i: Int)
        fun getStartMode(): Int                       // -1 = последний использованный
        fun setStartMode(i: Int)
        fun getCurrentMode(): Int
        fun getModeParam(i: Int): Float
        fun setModeParam(i: Int, v: Float)
        fun getPixelRows(): Int
        fun setPixelRows(rows: Int)
        fun getBorder(): Int
        fun setBorder(px: Int)
        fun getBeatSensitivity(): Float
        fun setBeatSensitivity(v: Float)
        fun isGestureLock(): Boolean
        fun setGestureLock(v: Boolean)
        fun resetTab(index: Int)                      // 0 = Видео, 1 = Аудио, 2 = Интерфейс
        fun resetAll()
        fun getSpeedMul(): Float                      // ползунок 0..1, 0,5 = x1
        fun setSpeedMul(v: Float)
        fun getReaction(): Float                      // 0 = резкая, 1 = плавная
        fun setReaction(v: Float)
        fun getMicSource(): Int                       // 0 = обычный, 1 = без обработки
        fun setMicSource(i: Int)
        fun isUnprocessedSupported(): Boolean
        fun getInputDeviceNames(): List<String>
        fun getMicDevice(): String                    // "auto" или название
        fun setMicDevice(name: String)
        fun isAutoGain(): Boolean
        fun setAutoGain(v: Boolean)
        fun getAutoStep(): Float
        fun isShowStartScreen(): Boolean
        fun setShowStartScreen(v: Boolean)
        fun showStartScreenNow()
        fun getPresetNames(): List<String>
        fun savePreset(name: String)
        fun loadPreset(name: String)
        fun deletePreset(name: String)
        fun onClose()
    }

    private val density = resources.displayMetrics.density
    private val accent = Color.rgb(51, 133, 255)
    private val tabTitles = arrayOf("Видео", "Аудио", "Интерфейс")
    private val tabViews = ArrayList<TextView>()
    private val content = LinearLayout(context)

    // индикатор уровня на вкладке «Аудио»: обновляется, пока панель видна
    private var meter: LevelMeterView? = null
    private var autoText: TextView? = null
    private val meterUpdater = object : Runnable {
        override fun run() {
            val m = meter ?: return
            if (!m.isShown) return
            m.update(host.getLevel(), host.getPeak())
            val at = autoText
            if (at != null) {
                at.text = if (host.isAutoGain()) {
                    "Автоподстройка: " + String.format(Locale.US, "%+.2f", host.getAutoStep()) + " ступени"
                } else {
                    "Автоусиление выключено"
                }
            }
            postDelayed(this, 50L)
        }
    }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        orientation = VERTICAL
        isClickable = true // не пропускать касания к картинке под панелью
        background = GradientDrawable().apply {
            setColor(Color.argb(235, 18, 18, 18))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))

        // заголовок + кнопка закрытия
        val header = LinearLayout(context)
        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context)
        title.text = "Настройки"
        title.setTextColor(Color.WHITE)
        title.textSize = 18f
        title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LinearLayout.LayoutParams(0, wrapContent, 1f))
        val close = TextView(context)
        close.text = "✕"
        close.setTextColor(Color.WHITE)
        close.textSize = 20f
        close.setPadding(dp(14), dp(4), dp(4), dp(4))
        close.setOnClickListener { host.onClose() }
        header.addView(close, LinearLayout.LayoutParams(wrapContent, wrapContent))
        addView(header, LinearLayout.LayoutParams(matchParent, wrapContent))

        // вкладки
        val tabs = LinearLayout(context)
        tabs.orientation = HORIZONTAL
        for (i in tabTitles.indices) {
            val tv = TextView(context)
            tv.text = tabTitles[i]
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(8), dp(10), dp(8), dp(10))
            tv.setOnClickListener { showTab(i) }
            tabViews.add(tv)
            tabs.addView(tv, LinearLayout.LayoutParams(0, wrapContent, 1f))
        }
        val tabsLp = LinearLayout.LayoutParams(matchParent, wrapContent)
        tabsLp.topMargin = dp(8)
        addView(tabs, tabsLp)

        // содержимое вкладки (прокручивается)
        content.orientation = VERTICAL
        val scroll = ScrollView(context)
        scroll.addView(content, ViewGroup.LayoutParams(matchParent, wrapContent))
        val scrollLp = LinearLayout.LayoutParams(matchParent, 0, 1f)
        scrollLp.topMargin = dp(8)
        addView(scroll, scrollLp)
    }

    /** Перечитать состояние и открыть последнюю вкладку. */
    fun refresh() {
        showTab(host.getLastTab().coerceIn(0, tabTitles.size - 1))
    }

    private fun showTab(index: Int) {
        removeCallbacks(meterUpdater)
        meter = null
        autoText = null
        host.setLastTab(index)
        for (i in tabViews.indices) {
            val selected = i == index
            tabViews[i].setTextColor(if (selected) Color.WHITE else Color.LTGRAY)
            tabViews[i].typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tabViews[i].setBackgroundColor(if (selected) Color.argb(90, 51, 133, 255) else Color.TRANSPARENT)
        }
        content.removeAllViews()
        when (index) {
            0 -> buildVideo()
            1 -> buildAudio()
            else -> buildInterface()
        }
    }

    // ---------- вкладки ----------

    /**
     * Яркость и множитель насыщенности пяти оттенков монохромной палитры.
     * Значения должны совпадать с блоком uPalette > 1.5 в Shaders.kt (индексы 0..4).
     */
    private val monoValue = floatArrayOf(0.20f, 0.85f, 0.45f, 1.00f, 0.65f)
    private val monoSatK = floatArrayOf(1.00f, 0.90f, 1.00f, 0.22f, 0.70f)

    private fun monoShade(index: Int, hue: Float, sat: Float): Int {
        val hsv = floatArrayOf(hue * 360f, (sat * monoSatK[index]).coerceIn(0f, 1f), monoValue[index])
        return Color.HSVToColor(hsv)
    }

    private fun buildVideo() {
        // блок монохромной палитры: виден только при выбранном «Монохромный»
        val monoBox = LinearLayout(context)
        monoBox.orientation = VERTICAL

        val swatchRow = LinearLayout(context)
        swatchRow.orientation = HORIZONTAL
        val swatches = ArrayList<View>()
        for (k in 0 until 5) {
            val v = View(context)
            swatches.add(v)
            val lp = LinearLayout.LayoutParams(0, dp(28), 1f)
            if (k > 0) lp.leftMargin = dp(4)
            swatchRow.addView(v, lp)
        }

        fun updateSwatches() {
            val order = intArrayOf(0, 2, 4, 1, 3) // от тёмного к светлому
            for (k in order.indices) {
                swatches[k].setBackgroundColor(monoShade(order[k], host.getMonoHue(), host.getMonoSat()))
            }
        }

        monoBox.addView(sectionLabel("Пять оттенков"))
        monoBox.addView(swatchRow, LinearLayout.LayoutParams(matchParent, wrapContent))

        monoBox.addView(sectionLabel("Цвет"))
        val hueBar = SeekBar(context)
        hueBar.max = 360
        hueBar.progress = (host.getMonoHue() * 360f).roundToInt().coerceIn(0, 360)
        hueBar.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
        ).apply { cornerRadius = dp(4).toFloat() }
        hueBar.progressDrawable = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        hueBar.setPadding(dp(14), 0, dp(14), 0)
        hueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    host.setMonoHue(progress / 360f)
                    updateSwatches()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        monoBox.addView(hueBar, LinearLayout.LayoutParams(matchParent, dp(32)))

        monoBox.addView(sectionLabel("Насыщенность"))
        val satBar = SeekBar(context)
        satBar.max = 100
        satBar.progress = (host.getMonoSat() * 100f).roundToInt().coerceIn(0, 100)
        satBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    host.setMonoSat(progress / 100f)
                    updateSwatches()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        monoBox.addView(satBar, LinearLayout.LayoutParams(matchParent, dp(32)))

        updateSwatches()
        monoBox.visibility = if (host.getPalette() == 2) View.VISIBLE else View.GONE

        content.addView(sectionLabel("Режим работы"))
        content.addView(
            segmented(arrayOf("Цветной", "ЧБ", "Монохромный"), host.getPalette().coerceIn(0, 2)) { k ->
                host.setPalette(k)
                monoBox.visibility = if (k == 2) View.VISIBLE else View.GONE
            }
        )
        content.addView(monoBox, LinearLayout.LayoutParams(matchParent, wrapContent))

        buildEffects()
        buildSpeed()
        buildStartMode()
        buildPixelSize()
        buildBorder()
        content.addView(resetButton("Сбросить вкладку «Видео»") {
            host.resetTab(0)
            refresh()
        }, resetLp())
    }

    // ---------- эффекты ----------

    private fun buildEffects() {
        content.addView(sectionLabel("Эффекты"))
        val overall = sliderRow("Общая сила (жест в центре экрана)", host.getOverall()) { host.setOverall(it) }
        content.addView(overall.first, LinearLayout.LayoutParams(matchParent, wrapContent))

        val names = arrayOf("VHS", "Glitch", "Noise")
        val infos = arrayOf(
            "плывущая полоса, scanlines, rolling bars, виньетка",
            "сдвиги строк, цветной глитч, блоки-помехи, мерцание, RGB-кайма",
            "аналоговый шум"
        )
        for (i in names.indices) {
            content.addView(effectBlock(i, names[i], infos[i]), LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private fun effectBlock(index: Int, name: String, info: String): View {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        box.setPadding(0, dp(10), 0, dp(4))

        val sw = Switch(context)
        sw.text = name
        sw.setTextColor(Color.WHITE)
        sw.textSize = 15f
        sw.isChecked = host.getEffectOn(index)

        val slider = sliderRow(null, host.getEffectStrength(index)) { host.setEffectStrength(index, it) }
        val rowView = slider.first
        val bar = slider.second

        fun applyEnabled(on: Boolean) {
            bar.isEnabled = on
            rowView.alpha = if (on) 1f else 0.4f
        }
        applyEnabled(sw.isChecked)
        sw.setOnCheckedChangeListener { _, checked ->
            host.setEffectOn(index, checked)
            applyEnabled(checked)
        }

        box.addView(sw, LinearLayout.LayoutParams(matchParent, wrapContent))
        box.addView(smallText(info))
        box.addView(rowView, LinearLayout.LayoutParams(matchParent, wrapContent))
        return box
    }

    /** Строка «ползунок 0..100% + значение». Возвращает (контейнер, ползунок). */
    private fun sliderRow(label: String?, value: Float, onChange: (Float) -> Unit): Pair<LinearLayout, SeekBar> {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        if (label != null) box.addView(smallText(label))

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val bar = SeekBar(context)
        bar.max = 100
        bar.progress = (value * 100f).roundToInt().coerceIn(0, 100)

        val pct = TextView(context)
        pct.text = bar.progress.toString() + "%"
        pct.setTextColor(Color.WHITE)
        pct.textSize = 13f
        pct.gravity = Gravity.END

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                pct.text = progress.toString() + "%"
                if (fromUser) onChange(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        row.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(pct, LinearLayout.LayoutParams(dp(48), wrapContent))
        box.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))
        return Pair(box, bar)
    }

    // ---------- скорость перестройки, источник микрофона, пресеты ----------

    private fun buildSpeed() {
        content.addView(sectionLabel("Скорость перестройки"))
        val valueText = TextView(context)
        valueText.setTextColor(Color.WHITE)
        valueText.textSize = 15f
        fun fmt(v: Float): String = String.format(Locale.US, "×%.2f", 2.0.pow(((v - 0.5f) * 4f).toDouble()))
        valueText.text = fmt(host.getSpeedMul())
        content.addView(valueText, LinearLayout.LayoutParams(matchParent, wrapContent))

        val bar = SeekBar(context)
        bar.max = 100
        bar.progress = (host.getSpeedMul() * 100f).roundToInt().coerceIn(0, 100)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueText.text = fmt(progress / 100f)
                if (fromUser) host.setSpeedMul(progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        content.addView(bar, LinearLayout.LayoutParams(matchParent, dp(36)))
        content.addView(smallText("От ×0,25 до ×4: как быстро меняются формы и цвета в режимах. Эффекты и удары не затрагиваются."))
    }

    private fun buildMicSource() {
        content.addView(sectionLabel("Источник микрофона"))
        content.addView(
            segmented(arrayOf("Обычный", "Без обработки"), host.getMicSource().coerceIn(0, 1)) { host.setMicSource(it) }
        )
        if (host.isUnprocessedSupported()) {
            content.addView(smallText("«Без обработки» отключает системное шумоподавление и автоусиление: лучше для музыки."))
        } else {
            content.addView(smallText("«Без обработки» на этом телефоне не поддерживается, будет использован обычный источник."))
        }

        content.addView(sectionLabel("Устройство ввода"))
        val names = host.getInputDeviceNames()
        val options = arrayOf("Автоматически") + names.toTypedArray()
        val current = host.getMicDevice()
        var selected = names.indexOf(current) + 1
        if (current == "auto" || selected < 0) selected = 0
        content.addView(choiceList(options, selected) { index ->
            host.setMicDevice(if (index == 0) "auto" else names[index - 1])
        })
        if (names.isEmpty()) {
            content.addView(smallText("Внешние устройства не найдены. Подключите USB-микрофон и откройте вкладку заново."))
        }
    }

    private fun simpleButton(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(12), dp(12), dp(12))
        tv.setBackgroundColor(Color.argb(60, 255, 255, 255))
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun buildPresets() {
        content.addView(sectionLabel("Пресеты"))
        val list = LinearLayout(context)
        list.orientation = VERTICAL

        fun fillList() {
            list.removeAllViews()
            val names = host.getPresetNames()
            if (names.isEmpty()) {
                list.addView(smallText("Пресетов пока нет."))
                return
            }
            for (name in names) {
                val row = LinearLayout(context)
                row.orientation = HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL

                val title = TextView(context)
                title.text = name
                title.setTextColor(Color.WHITE)
                title.textSize = 14f
                row.addView(title, LinearLayout.LayoutParams(0, wrapContent, 1f))

                val load = simpleButton("Загрузить") {
                    host.loadPreset(name)
                    refresh()
                }
                row.addView(load, LinearLayout.LayoutParams(wrapContent, wrapContent))

                val del = resetButton("✕") {
                    host.deletePreset(name)
                    fillList()
                }
                del.setPadding(dp(14), dp(12), dp(14), dp(12))
                val delLp = LinearLayout.LayoutParams(wrapContent, wrapContent)
                delLp.leftMargin = dp(6)
                row.addView(del, delLp)

                val lp = LinearLayout.LayoutParams(matchParent, wrapContent)
                lp.topMargin = dp(6)
                list.addView(row, lp)
            }
        }

        content.addView(simpleButton("Сохранить текущие настройки…") {
            val input = EditText(context)
            input.hint = "Название"
            input.setSingleLine(true)
            input.filters = arrayOf(InputFilter.LengthFilter(24))
            AlertDialog.Builder(context)
                .setTitle("Название пресета")
                .setView(input)
                .setPositiveButton("Сохранить") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        host.savePreset(name)
                        fillList()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }, resetLp())
        content.addView(list, LinearLayout.LayoutParams(matchParent, wrapContent))
        content.addView(smallText("В пресет входят настройки всех трёх вкладок, кроме выбранного устройства ввода."))
        fillList()
    }

    // ---------- режим при старте, размер «пикселя», рамка ----------

    private fun buildStartMode() {
        content.addView(sectionLabel("Режим при старте"))
        val paramBox = LinearLayout(context)
        paramBox.orientation = VERTICAL

        fun fillParam() {
            paramBox.removeAllViews()
            val startMode = host.getStartMode()
            val m = if (startMode >= 0) startMode else host.getCurrentMode()
            val label = "Параметр: " + GLRenderer.PARAM_NAMES[m] + " (" + GLRenderer.MODE_NAMES[m] + ")"
            val row = sliderRow(label, host.getModeParam(m)) { host.setModeParam(m, it) }
            paramBox.addView(row.first, LinearLayout.LayoutParams(matchParent, wrapContent))
        }

        val options = arrayOf("Последний использованный") + GLRenderer.MODE_NAMES
        content.addView(choiceList(options, host.getStartMode() + 1) { index ->
            host.setStartMode(index - 1)
            fillParam()
        })
        fillParam()
        content.addView(paramBox, LinearLayout.LayoutParams(matchParent, wrapContent))
    }

    private fun buildPixelSize() {
        content.addView(sectionLabel("Размер «пикселя» (строк по высоте)"))
        val rowsOptions = intArrayOf(135, 270, 540)
        var selected = rowsOptions.indexOf(host.getPixelRows())
        if (selected < 0) selected = 1
        content.addView(segmented(arrayOf("135", "270", "540"), selected) { host.setPixelRows(rowsOptions[it]) })
        content.addView(smallText("Меньше строк: крупнее «пиксель» и легче нагрузка. Больше строк: мельче и чётче."))
    }

    private fun stepButton(text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.setBackgroundColor(Color.argb(60, 255, 255, 255))
        return tv
    }

    private fun buildBorder() {
        content.addView(sectionLabel("Рамка (пикселей)"))
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val minus = stepButton("−")
        val plus = stepButton("+")
        val bar = SeekBar(context)
        bar.max = 300
        bar.progress = host.getBorder().coerceIn(0, 300)
        val value = TextView(context)
        value.setTextColor(Color.WHITE)
        value.textSize = 13f
        value.gravity = Gravity.END
        value.text = bar.progress.toString() + " px"

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                value.text = progress.toString() + " px"
                if (fromUser) host.setBorder(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        minus.setOnClickListener {
            val v = (bar.progress - 1).coerceAtLeast(0)
            bar.progress = v
            host.setBorder(v)
        }
        plus.setOnClickListener {
            val v = (bar.progress + 1).coerceAtMost(300)
            bar.progress = v
            host.setBorder(v)
        }

        row.addView(minus, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(value, LinearLayout.LayoutParams(dp(64), wrapContent))
        content.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))
        content.addView(
            smallText("Чёрная рамка со всех сторон, не больше 40% меньшей стороны экрана. Жесты работают по всему экрану.")
        )
    }

    private fun choiceList(options: Array<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(context)
        box.orientation = VERTICAL
        val items = ArrayList<TextView>()

        fun style(sel: Int) {
            for (k in items.indices) {
                items[k].setBackgroundColor(if (k == sel) accent else Color.argb(60, 255, 255, 255))
                items[k].setTextColor(Color.WHITE)
            }
        }

        for (k in options.indices) {
            val tv = TextView(context)
            tv.text = options[k]
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setPadding(dp(12), dp(10), dp(12), dp(10))
            tv.setOnClickListener {
                style(k)
                onSelect(k)
            }
            items.add(tv)
            val lp = LinearLayout.LayoutParams(matchParent, wrapContent)
            if (k > 0) lp.topMargin = dp(4)
            box.addView(tv, lp)
        }
        style(selected)
        return box
    }

    private fun resetLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(matchParent, wrapContent)
        lp.topMargin = dp(16)
        return lp
    }

    /** Кнопка сброса с подтверждением: первое нажатие «взводит» её на 2,5 секунды. */
    private fun resetButton(label: String, onConfirm: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(12), dp(12), dp(12), dp(12))
        tv.setBackgroundColor(Color.argb(60, 255, 255, 255))
        var armed = false
        val revert = Runnable {
            armed = false
            tv.text = label
            tv.setBackgroundColor(Color.argb(60, 255, 255, 255))
        }
        tv.setOnClickListener {
            if (armed) {
                tv.removeCallbacks(revert)
                revert.run()
                onConfirm()
            } else {
                armed = true
                tv.text = "Нажмите ещё раз для сброса"
                tv.setBackgroundColor(Color.rgb(160, 50, 40))
                tv.postDelayed(revert, 2500L)
            }
        }
        return tv
    }

    private fun smallText(text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(Color.GRAY)
        tv.textSize = 12f
        tv.setPadding(0, dp(2), 0, dp(2))
        return tv
    }

    private fun gainText(step: Float): String =
        String.format(Locale.getDefault(), "%+.2f  (×%.2f)", step, 2.0.pow(step.toDouble()))

    private fun buildAudio() {
        content.addView(sectionLabel("Усиление микрофона"))

        val valueText = TextView(context)
        valueText.setTextColor(Color.WHITE)
        valueText.textSize = 15f
        valueText.text = gainText(host.getGainStep())
        content.addView(valueText, LinearLayout.LayoutParams(matchParent, wrapContent))

        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val bar = SeekBar(context)
        bar.max = 16 // от -2 до +2 с шагом 0,25
        bar.progress = ((host.getGainStep() + 2f) * 4f).roundToInt().coerceIn(0, 16)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val step = progress / 4f - 2f
                valueText.text = gainText(step)
                if (fromUser) host.setGainStep(step)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        row.addView(bar, LinearLayout.LayoutParams(0, dp(36), 1f))

        val reset = TextView(context)
        reset.text = "0"
        reset.setTextColor(Color.WHITE)
        reset.textSize = 15f
        reset.gravity = Gravity.CENTER
        reset.setBackgroundColor(Color.argb(60, 255, 255, 255))
        reset.setPadding(dp(14), dp(6), dp(14), dp(6))
        reset.setOnClickListener {
            bar.progress = 8
            host.setGainStep(0f)
            valueText.text = gainText(0f)
        }
        val resetLp = LinearLayout.LayoutParams(wrapContent, wrapContent)
        resetLp.leftMargin = dp(8)
        row.addView(reset, resetLp)
        content.addView(row, LinearLayout.LayoutParams(matchParent, wrapContent))

        content.addView(sectionLabel("Уровень входного сигнала"))
        val m = LevelMeterView(context)
        content.addView(m, LinearLayout.LayoutParams(matchParent, dp(28)))
        content.addView(
            smallText("Подберите усиление так, чтобы полоса доходила до жёлтой зоны, а красный квадрат справа не загорался.")
        )
        meter = m
        post(meterUpdater)

        content.addView(
            switchRow("Автоусиление", host.isAutoGain()) { host.setAutoGain(it) },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(smallText("Медленно подгоняет уровень поверх ручного усиления в пределах ±2 ступеней."))
        val at = TextView(context)
        at.setTextColor(Color.LTGRAY)
        at.textSize = 12f
        at.setPadding(0, dp(2), 0, dp(2))
        content.addView(at)
        autoText = at

        content.addView(sectionLabel("Чувствительность удара"))
        val beat = sliderRow(null, host.getBeatSensitivity()) { host.setBeatSensitivity(it) }
        content.addView(beat.first, LinearLayout.LayoutParams(matchParent, wrapContent))
        content.addView(smallText("Выше: удар срабатывает чаще и на более слабые акценты."))

        content.addView(sectionLabel("Реакция на звук"))
        val reaction = sliderRow(null, host.getReaction()) { host.setReaction(it) }
        content.addView(reaction.first, LinearLayout.LayoutParams(matchParent, wrapContent))
        content.addView(smallText("0%: резкая, 100%: плавная (скорость подъёма и спада значений)."))

        buildMicSource()

        content.addView(resetButton("Сбросить вкладку «Аудио»") {
            host.resetTab(1)
            refresh()
        }, resetLp())
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val sw = Switch(context)
        sw.text = text
        sw.setTextColor(Color.WHITE)
        sw.textSize = 15f
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        sw.setPadding(0, dp(12), 0, dp(4))
        return sw
    }

    private fun buildInterface() {
        content.addView(
            switchRow("Пауза по двойному тапу", host.isPauseEnabled()) { host.setPauseEnabled(it) },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(smallText("Двойной тап ставит картинку на паузу и снимает её."))

        content.addView(
            switchRow("OSD (информация на экране)", host.isOsdOn()) { host.setOsdOn(it) },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(smallText("Скрывает индикаторы и сообщения на экране. Жесты продолжают работать, ошибки показываются всегда."))

        content.addView(
            switchRow("Защита от выхода", host.isExitGuard()) { host.setExitGuard(it) },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(smallText("Первое нажатие «Назад» показывает подсказку, второе в течение 2 секунд закрывает приложение."))

        content.addView(
            switchRow("Блокировка жестов", host.isGestureLock()) { host.setGestureLock(it) },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(smallText("Отключает все жесты на экране, кроме тапа двумя пальцами (меню)."))

        content.addView(
            switchRow("Показывать при запуске", host.isShowStartScreen()) { host.setShowStartScreen(it) },
            LinearLayout.LayoutParams(matchParent, wrapContent)
        )
        content.addView(smallText("Начальный экран с краткой инструкцией. Показывается независимо от OSD."))
        content.addView(simpleButton("Показать инструкцию") { host.showStartScreenNow() }, resetLp())

        buildPresets()

        content.addView(hint("Меню открывается и закрывается тапом двумя пальцами."))

        content.addView(resetButton("Сбросить вкладку «Интерфейс»") {
            host.resetTab(2)
            refresh()
        }, resetLp())
        content.addView(resetButton("Сбросить все настройки") {
            host.resetAll()
            refresh()
        }, resetLp())
    }

    // ---------- элементы ----------

    private fun sectionLabel(text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(Color.LTGRAY)
        tv.textSize = 13f
        tv.setPadding(0, dp(12), 0, dp(6))
        return tv
    }

    private fun hint(text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextColor(Color.GRAY)
        tv.textSize = 13f
        tv.setPadding(0, dp(16), 0, 0)
        return tv
    }

    private fun segmented(options: Array<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        val items = ArrayList<TextView>()

        fun style(sel: Int) {
            for (k in items.indices) {
                items[k].setBackgroundColor(if (k == sel) accent else Color.argb(60, 255, 255, 255))
                items[k].setTextColor(Color.WHITE)
            }
        }

        for (k in options.indices) {
            val tv = TextView(context)
            tv.text = options[k]
            tv.textSize = 14f
            tv.gravity = Gravity.CENTER
            tv.setPadding(dp(8), dp(12), dp(8), dp(12))
            tv.setOnClickListener {
                style(k)
                onSelect(k)
            }
            items.add(tv)
            val lp = LinearLayout.LayoutParams(0, wrapContent, 1f)
            if (k > 0) lp.leftMargin = dp(4)
            row.addView(tv, lp)
        }
        style(selected)
        return row
    }
}
