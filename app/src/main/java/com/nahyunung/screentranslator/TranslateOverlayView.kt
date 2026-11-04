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
    initialItems: List<OverlayItem>,
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

    private val items = initialItems.toMutableList()
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
    /** 스트리밍으로 들어온 박스 하나 추가 */
    fun addItem(item: OverlayItem) {
        items.add(item)
        placed?.let { list ->
            getLocationOnScreen(loc)
            // 잡고 있는 박스가 계속 맨 위에 오도록 그 아래에 끼워 넣음
            val idx = dragging?.let { list.indexOf(it) }?.takeIf { it >= 0 } ?: list.size
            list.add(idx, placeOne(item, loc[0] + width))
        }
        invalidate()
    }

    private fun place(screenW: Int): MutableList<Placed> =
        items.map { placeOne(it, screenW) }.toMutableList()

    private fun placeOne(item: OverlayItem, screenW: Int): Placed {
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
        return Placed(RectF(left, r.top.toFloat(), left + boxW, r.top + boxH), layout)
    }

    private fun ensurePlaced(): MutableList<Placed> {
        getLocationOnScreen(loc)
        return placed ?: place(loc[0] + width).also { placed = it }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placed = null
    }

    // 박스 탭 → 선택(지우기 버튼 표시)
    private var selected: Placed? = null
    private val deleteRect = RectF()  // 지우기 버튼 위치 (화면 좌표)
    private var pressingDelete = false

    private val deleteBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val deleteText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * scaled
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** 선택된 박스의 오른쪽 위에 "✕ 지우기" 버튼 위치 계산 (화면 밖으로 안 나가게) */
    private fun updateDeleteRect(p: Placed) {
        val w = deleteText.measureText("✕ 지우기") + 20 * density
        val h = 30 * density
        val screenRight = (loc[0] + width).toFloat()
        var left = p.box.right - w
        var top = p.box.top - h - 4 * density
        if (top < loc[1] + 4 * density) top = p.box.bottom + 4 * density // 위에 자리 없으면 아래로
        left = left.coerceIn(loc[0].toFloat(), screenRight - w)
        deleteRect.set(left, top, left + w, top + h)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val list = ensurePlaced()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                lastX = e.rawX; lastY = e.rawY
                moved = false
                // 지우기 버튼 먼저 검사
                pressingDelete = selected != null && deleteRect.contains(e.rawX, e.rawY)
                if (pressingDelete) return true
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
                if (pressingDelete) return true
                if (!moved && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) moved = true
                if (moved) {
                    dragging?.box?.offset(e.rawX - lastX, e.rawY - lastY)
                    invalidate()
                }
                lastX = e.rawX; lastY = e.rawY
            }
            MotionEvent.ACTION_UP -> {
                if (pressingDelete) {
                    pressingDelete = false
                    val target = selected
                    if (target != null && deleteRect.contains(e.rawX, e.rawY)) {
                        list.remove(target)
                        selected = null
                        if (list.isEmpty()) { onClose(); return true } // 다 지우면 닫기
                    }
                    invalidate()
                    return true
                }
                val d = dragging
                when {
                    // 박스를 그냥 탭 → 선택/선택 해제 (지우기 버튼 토글)
                    d != null && !moved -> selected = if (selected === d) null else d
                    // 박스를 끌었으면 그 박스를 선택 상태로 유지하지 않음
                    d != null -> if (selected === d) selected = null
                    // 빈 곳 탭: 선택 중이면 선택만 해제, 아니면 닫기
                    !moved -> if (selected != null) selected = null else onClose()
                }
                dragging = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                pressingDelete = false
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
            canvas.drawRoundRect(p.box, radius, radius,
                if (p === dragging || p === selected) dragBorderPaint else borderPaint)
            canvas.save()
            val dy = (p.box.height() - p.layout.height) / 2f
            canvas.translate(p.box.left + pad, p.box.top + max(pad, dy))
            p.layout.draw(canvas)
            canvas.restore()
        }
        // 선택된 박스의 지우기 버튼
        selected?.let { p ->
            if (p in list) {
                updateDeleteRect(p)
                canvas.drawRoundRect(deleteRect, deleteRect.height() / 2, deleteRect.height() / 2, deleteBg)
                canvas.drawText(
                    "✕ 지우기", deleteRect.centerX(),
                    deleteRect.centerY() + deleteText.textSize / 3, deleteText
                )
            } else selected = null
        }
        canvas.restore()

        // 하단 안내
        val hint = if (selected != null) "✕ 지우기 누르면 이 박스 삭제 · 빈 곳 탭하면 취소"
                   else "박스 끌기=이동 · 박스 탭=지우기 · 빈 곳 탭=닫기"
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
