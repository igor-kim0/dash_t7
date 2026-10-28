package com.dash.headunit.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.dash.headunit.R
import kotlin.math.roundToInt

/**
 * Рамка виджета: карточка с фоном темы + контент.
 *
 * В режиме редактирования ([editing] = true):
 * - перетаскивание за любую точку двигает виджет по сетке;
 * - перетаскивание за правый нижний угол (зона [HANDLE_DP]) меняет размер,
 *   сам угол подсвечен белым контуром-подсказкой;
 * - в левом верхнем углу показан бейдж удаления ([onDelete]);
 * - в правом верхнем углу — бейдж настроек, если виджет их поддерживает
 *   ([onSettings] != null);
 * - дочерние вью клики не получают.
 *
 * После отпускания пальца вызывает [onGeometryChanged] — Activity сохраняет раскладку.
 */
class WidgetFrame(
    context: Context,
    val widgetId: String,
    val widgetType: WidgetType,
    private var editing: Boolean = false,
    private val theme: com.dash.headunit.theme.DashTheme = com.dash.headunit.theme.DashTheme.DARK,
    /** Вид «Ретро» ([com.dash.headunit.theme.RetroLook]): фон — табличка в рамке. */
    private val retro: Boolean = false,
    var onGeometryChanged: (() -> Unit)? = null,
    var onDelete: (() -> Unit)? = null,
    var onSettings: (() -> Unit)? = null
) : FrameLayout(context) {

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics
        )
        color = 0xFFFFFFFF.toInt()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val handleDp = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, HANDLE_DP, resources.displayMetrics
    )

    // Состояние драга.
    private var dragMode: DragMode = DragMode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startCellX = 0
    private var startCellY = 0
    private var startCellW = 0
    private var startCellH = 0

    private enum class DragMode { NONE, MOVE, RESIZE }

    var contentRoot: View? = null
        private set

    /**
     * В режиме редактирования перехватываем все жесты на фрейм —
     * иначе сложные дочерние вью (карта и т.п.) съедят драг/ресайз.
     * Тап по бейджу удаления пропускается.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return super.onInterceptTouchEvent(event)
        return when (event.actionMasked) {
            // DOWN на бейдже — пропускаем ему, иначе забираем для драга.
            MotionEvent.ACTION_DOWN ->
                !(isOnDeleteBadge(event.x, event.y) || isOnSettingsBadge(event.x, event.y))
            // MOVE всегда наш (драг/ресайз). UP/CANCEL — тому, кто получил DOWN,
            // иначе клики по бейджам отменялись бы.
            MotionEvent.ACTION_MOVE -> true
            else -> false
        }
    }

    private fun isOnDeleteBadge(x: Float, y: Float): Boolean {
        if (deleteBadge.visibility != View.VISIBLE) return false
        return x >= deleteBadge.left && x <= deleteBadge.right &&
            y >= deleteBadge.top && y <= deleteBadge.bottom
    }

    private fun isOnSettingsBadge(x: Float, y: Float): Boolean {
        if (settingsBadge.visibility != View.VISIBLE) return false
        return x >= settingsBadge.left && x <= settingsBadge.right &&
            y >= settingsBadge.top && y <= settingsBadge.bottom
    }

    /** Бейдж удаления (виден только в режиме редактирования). */
    private val deleteBadge = android.widget.TextView(context).apply {
        text = "✕"
        gravity = android.view.Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setBackgroundResource(R.drawable.delete_badge)
        contentDescription = context.getString(R.string.cd_delete)
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics
        ).toInt()
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, -14f, resources.displayMetrics
        ).toInt()
        layoutParams = LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.START).apply {
            setMargins(margin, margin, margin, margin)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onDelete?.invoke() }
        visibility = if (editing) View.VISIBLE else View.GONE
    }

    /** Бейдж настроек (виден в режиме редактирования, если есть [onSettings]). */
    private val settingsBadge = android.widget.ImageButton(context).apply {
        setImageResource(R.drawable.ic_gear)
        contentDescription = context.getString(R.string.cd_settings)
        setBackgroundResource(R.drawable.delete_badge)
        setColorFilter(0xFFFFFFFF.toInt())
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics
        ).toInt()
        val margin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, -14f, resources.displayMetrics
        ).toInt()
        layoutParams = LayoutParams(size, size, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            setMargins(margin, margin, margin, margin)
        }
        setPadding(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9f, resources.displayMetrics).toInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9f, resources.displayMetrics).toInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9f, resources.displayMetrics).toInt(),
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 9f, resources.displayMetrics).toInt()
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onSettings?.invoke() }
        visibility = View.GONE
    }

    init {
        background = baseBackground()
        // Бейдж наполовину выступает за край — не обрезаем его.
        clipChildren = false
        clipToPadding = false
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 12f, resources.displayMetrics
        ).toInt()
        setPadding(pad, pad, pad, pad)
    }

    fun setContent(view: View) {
        contentRoot = view
        removeAllViews()
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Бейджи всегда поверх контента.
        addView(deleteBadge)
        addView(settingsBadge)
        // Применить текущее состояние (фрейм могли создать уже в режиме редактирования).
        setEditMode(editing)
    }

    fun setEditMode(enabled: Boolean) {
        editing = enabled
        // В режиме редактирования перехватываем все тачи сами.
        isClickable = enabled
        isFocusable = enabled
        // Контент (у него могут быть свои click-listener'ы, напр. "обновить
        // по тапу") в режиме редактирования тачи получать не должен,
        // иначе драг/ресайз до фрейма не дойдёт.
        contentRoot?.isClickable = !enabled
        contentRoot?.isFocusable = !enabled
        restoreBackground()
        deleteBadge.visibility = if (enabled) View.VISIBLE else View.GONE
        deleteBadge.bringToFront()
        settingsBadge.visibility =
            if (enabled && onSettings != null) View.VISIBLE else View.GONE
        settingsBadge.bringToFront()
        invalidate()
    }

    /**
     * Фон блока по теме. В плоской теме при редактировании — карточка:
     * видно, за что тянуть.
     */
    fun restoreBackground() {
        background = if (editing && !retro) com.dash.headunit.theme.ThemeCard.card(context, theme)
        else baseBackground()
    }

    /** Фон вне редактирования: ретро-табличка или карточка темы (в плоской — нет). */
    private fun baseBackground() =
        if (retro) com.dash.headunit.theme.RetroLook.panel(context)
        else com.dash.headunit.theme.ThemeCard.widget(context, theme)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(event)
        val grid = parent as? DashboardGridLayout ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX
                downRawY = event.rawY
                val lp = layoutParams as DashboardGridLayout.LayoutParams
                startCellX = lp.cellX
                startCellY = lp.cellY
                startCellW = lp.cellW
                startCellH = lp.cellH
                dragMode = if (event.x >= width - handleDp && event.y >= height - handleDp) {
                    DragMode.RESIZE
                } else {
                    DragMode.MOVE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return true
                val (cellW, cellH) = grid.cellSizePx()
                if (cellW <= 0 || cellH <= 0) return true
                val dxCells = ((event.rawX - downRawX) / cellW).roundToInt()
                val dyCells = ((event.rawY - downRawY) / cellH).roundToInt()
                when (dragMode) {
                    DragMode.MOVE -> grid.updateChild(
                        this,
                        startCellX + dxCells,
                        startCellY + dyCells,
                        startCellW,
                        startCellH
                    )
                    DragMode.RESIZE -> grid.updateChild(
                        this,
                        startCellX,
                        startCellY,
                        startCellW + dxCells,
                        startCellH + dyCells
                    )
                    DragMode.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) onGeometryChanged?.invoke()
                dragMode = DragMode.NONE
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        // Скруглённая скобка-подсказка ровно по контуру скругления карточки:
        // радиус тот же, что у фона (12dp), плюс короткие прямые участки.
        val metrics = resources.displayMetrics
        val radius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, CORNER_RADIUS_DP, metrics
        )
        val ext = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, CORNER_EXT_DP, metrics
        )
        val w = width.toFloat()
        val h = height.toFloat()
        val oval = android.graphics.RectF(w - 2 * radius, h - 2 * radius, w, h)
        val path = android.graphics.Path().apply {
            moveTo(w - radius - ext, h)
            lineTo(w - radius, h)
            arcTo(oval, 90f, -90f, false)
            lineTo(w, h - radius - ext)
        }
        canvas.drawPath(path, cornerPaint)
    }

    companion object {
        private const val HANDLE_DP = 56f
        /** Радиус скругления скобки = радиусу фона карточки (widget_card). */
        private const val CORNER_RADIUS_DP = 12f
        /** Длина прямых участков скобки вдоль краёв. */
        private const val CORNER_EXT_DP = 10f
    }
}
