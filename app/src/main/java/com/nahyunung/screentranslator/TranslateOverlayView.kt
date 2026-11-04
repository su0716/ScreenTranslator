package com.nahyunung.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 번역된 글자 덩어리 하나 (rect는 실제 화면 좌표) */
data class OverlayItem(val rect: Rect, val text: String, val lineCount: Int)

/**
 * 원래 글자 위치에 흰 박스 + 번역문을 덮어 그리는 전체 화면 뷰.
 * - 박스를 끌면 이동 (밑에 원문 확인용)
 * - 박스 밖 빈 곳을 탭하면 닫힘
 */
@SuppressLint("ViewConstructor")
class TranslateOverlayView(
    context: Context,
    private val items: List<OverlayItem>,
    private val onClose: () -> Unit
) : View(context) {

    private class Placed(val box: RectF, val layout: StaticLayout)

    private val density = resources.displayMetrics.density
    @Suppress("DEPRECATION")
    private val scaled = resources.displayMetrics.scaledDensity
    private val pad = 3 * density
    private val radius = 4 * density
    private val minText = 9 * scaled
    private val maxText = 26 * scaled
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 255, 255) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = Color.argb(90, 47, 107, 255)
    }
    private val dragBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.rgb(47, 107, 255)
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * scaled
        textAlign = Paint.Align.CENTER
    }

    private var placed: MutableList<Placed>? = null
    private val loc = IntArray(2)

    // 드래그 상태 (좌표는 모두 화면 기준 raw 좌표)
    private var dragging: Placed? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    private fun build(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    /** 각 박스에 들어가도록 글자 크기를 줄여가며 맞춤. 그래도 넘치면 박스를 아래로 늘림. */
    private fun place(screenW: Int): MutableList<Placed> = items.map { item ->
        val r = item.rect
        var boxW = max(r.width().toFloat(), 48 * density)
        // 세로쓰기 칸(좁고 긴 박스)은 한국어 가로쓰기가 들어가도록 넓혀서 가운데 정렬
        val vertical = r.height() > r.width() * 1.6f
        if (vertical) boxW = max(boxW, min(r.height() * 0.7f, screenW * 0.45f))
        val innerW = (boxW - 2 * pad).toInt()
        val innerH = r.height() - 2 * pad

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 17, 17) }
        var size = (r.height().toFloat() / item.lineCount * 0.8f).coerceIn(minText, maxText)
        paint.textSize = size
        var layout = build(item.text, paint, innerW)
        while (layout.height > innerH && size > minText) {
            size = max(minText, size - 0.5f * scaled)
            paint.textSize = size
            layout = build(item.text, paint, innerW)
        }

        var left = if (vertical) r.exactCenterX() - boxW / 2 else r.left.toFloat()
        if (left + boxW > screenW) left = screenW - boxW
        left = max(0f, left)
        // 세로 박스는 글이 들어갈 만큼만 높이 사용, 가로 박스는 원문을 덮도록
        val boxH = if (vertical) layout.height + 2 * pad
                   else max(r.height().toFloat(), layout.height + 2 * pad)
        Placed(RectF(left, r.top.toFloat(), left + boxW, r.top + boxH), layout)
    }.toMutableList()

    private fun ensurePlaced(): MutableList<Placed> {
        getLocationOnScreen(loc)
        return placed ?: place(loc[0] + width).also { placed = it }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placed = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val list = ensurePlaced()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                lastX = e.rawX; lastY = e.rawY
                moved = false
                // 맨 위에 그려진 박스부터 검사
                dragging = list.lastOrNull { it.box.contains(e.rawX, e.rawY) }
                dragging?.let {
                    // 잡은 박스를 맨 위로
                    list.remove(it)
                    list.add(it)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moved = true
                if (moved) {
                    dragging?.box?.offset(e.rawX - lastX, e.rawY - lastY)
                    invalidate()
                }
                lastX = e.rawX; lastY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                // 빈 곳을 그냥 탭했을 때만 닫기
                if (dragging == null && !moved) onClose()
                dragging = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 이 창이 화면의 (0,0)에서 시작하지 않을 수도 있어서 실제 위치만큼 보정
        val list = ensurePlaced()

        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (p in list) {
            canvas.drawRoundRect(p.box, radius, radius, bgPaint)
            canvas.drawRoundRect(p.box, radius, radius, if (p === dragging) dragBorderPaint else borderPaint)
            canvas.save()
            val dy = (p.box.height() - p.layout.height) / 2f
            canvas.translate(p.box.left + pad, p.box.top + max(pad, dy))
            p.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        // 하단 안내
        val hint = "박스는 끌어서 이동 · 빈 곳 탭하면 닫힘"
        val tw = hintPaint.measureText(hint)
        val cx = width / 2f
        val cy = height - 64 * density
        val hpad = 12 * density
        canvas.drawRoundRect(
            cx - tw / 2 - hpad, cy - 22 * density, cx + tw / 2 + hpad, cy + 10 * density,
            16 * density, 16 * density, hintBg
        )
        canvas.drawText(hint, cx, cy, hintPaint)
    }
}
