package com.nahyunung.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 번역할 영역을 고르는 전체 화면 뷰.
 * - 빈 곳을 드래그하면 새 영역
 * - 영역 안을 드래그하면 영역 이동
 * - 위쪽 안내바의 ✕를 누르면 취소
 * 좌표는 모두 화면 기준(raw) 좌표.
 */
@SuppressLint("ViewConstructor")
class RegionSelectView(
    context: Context,
    initial: Rect?,
    private val onCancel: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    @Suppress("DEPRECATION")
    private val scaled = resources.displayMetrics.scaledDensity
    private val minSize = 40 * density

    /** 지금 선택된 영역 (화면 좌표) */
    private var region: RectF? = initial?.let { RectF(it) }

    private val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.rgb(47, 107, 255)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(47, 107, 255) }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 20, 20, 24) }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14 * scaled
    }
    private val closeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18 * scaled
        textAlign = Paint.Align.CENTER
    }

    private val loc = IntArray(2)
    private val chipRect = RectF()
    private val closeRect = RectF()

    private enum class Mode { NONE, DRAW, MOVE }
    private var mode = Mode.NONE
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** 최종 영역 (너무 작으면 null) */
    fun selectedRect(): Rect? {
        val r = region ?: return null
        if (r.width() < minSize || r.height() < minSize) return null
        return Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.rawX
        val y = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 안내바 ✕ 누르면 취소
                getLocationOnScreen(loc)
                if (closeRect.contains(x - loc[0], y - loc[1])) {
                    onCancel()
                    return true
                }
                startX = x; startY = y; lastX = x; lastY = y
                mode = if (region?.contains(x, y) == true) Mode.MOVE else Mode.DRAW
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAW -> {
                        if (abs(x - startX) > 4 * density || abs(y - startY) > 4 * density) {
                            region = RectF(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                        }
                    }
                    Mode.MOVE -> region?.offset(x - lastX, y - lastY)
                    Mode.NONE -> Unit
                }
                lastX = x; lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()

        // 선택 영역 바깥만 어둡게
        val r = region?.let { RectF(it.left - ox, it.top - oy, it.right - ox, it.bottom - oy) }
        if (r == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        } else {
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addRect(r, Path.Direction.CW)
            }
            canvas.drawPath(path, dimPaint)
            canvas.drawRect(r, borderPaint)
            val c = 5 * density
            for (px in listOf(r.left, r.right)) for (py in listOf(r.top, r.bottom)) {
                canvas.drawCircle(px, py, c, cornerPaint)
            }
        }

        // 위쪽 안내바 + ✕
        val msg = if (region == null) "번역할 부분을 드래그해" else "번역 버튼을 누르면 보내져 · 안을 끌면 이동"
        val pad = 12 * density
        val tw = chipText.measureText(msg)
        val closeW = 36 * density
        val chipW = tw + pad * 2 + closeW
        val top = 56 * density
        val chipH = 40 * density
        chipRect.set((width - chipW) / 2, top, (width + chipW) / 2, top + chipH)
        canvas.drawRoundRect(chipRect, chipH / 2, chipH / 2, chipBg)
        canvas.drawText(msg, chipRect.left + pad, chipRect.centerY() + chipText.textSize / 3, chipText)
        closeRect.set(chipRect.right - closeW - 4 * density, chipRect.top, chipRect.right, chipRect.bottom)
        canvas.drawText("✕", closeRect.centerX(), closeRect.centerY() + closeText.textSize / 3, closeText)
    }
}
