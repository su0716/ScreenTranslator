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
import android.view.View
import kotlin.math.max

/** 번역된 글자 덩어리 하나 (rect는 실제 화면 좌표) */
data class OverlayItem(val rect: Rect, val text: String, val lineCount: Int)

/**
 * 원래 글자 위치에 흰 박스 + 번역문을 덮어 그리는 전체 화면 뷰.
 * 아무 데나 탭하면 닫힘.
 */
@SuppressLint("ViewConstructor")
class TranslateOverlayView(
    context: Context,
    private val items: List<OverlayItem>,
    private val onClose: () -> Unit
) : View(context) {

    private class Placed(val box: RectF, val layout: StaticLayout)

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val pad = 3 * density
    private val radius = 4 * density
    private val minText = 9 * scaled
    private val maxText = 26 * scaled

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 255, 255, 255) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = Color.argb(90, 47, 107, 255)
    }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0) }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * scaled
        textAlign = Paint.Align.CENTER
    }

    private var placed: List<Placed>? = null
    private val loc = IntArray(2)

    init {
        isClickable = true
        setOnClickListener { onClose() }
    }

    private fun build(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()

    /** 각 박스에 들어가도록 글자 크기를 줄여가며 맞춤. 그래도 넘치면 박스를 아래로 늘림. */
    private fun place(screenW: Int): List<Placed> = items.map { item ->
        val r = item.rect
        val boxW = max(r.width().toFloat(), 48 * density)
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

        var left = r.left.toFloat()
        if (left + boxW > screenW) left = max(0f, screenW - boxW)
        val boxH = max(r.height().toFloat(), layout.height + 2 * pad)
        Placed(RectF(left, r.top.toFloat(), left + boxW, r.top + boxH), layout)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placed = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 이 창이 화면의 (0,0)에서 시작하지 않을 수도 있어서 실제 위치만큼 보정
        getLocationOnScreen(loc)
        val list = placed ?: place(loc[0] + width).also { placed = it }

        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (p in list) {
            canvas.drawRoundRect(p.box, radius, radius, bgPaint)
            canvas.drawRoundRect(p.box, radius, radius, borderPaint)
            canvas.save()
            val dy = (p.box.height() - p.layout.height) / 2f
            canvas.translate(p.box.left + pad, p.box.top + max(pad, dy))
            p.layout.draw(canvas)
            canvas.restore()
        }
        canvas.restore()

        // 하단 안내
        val hint = "탭하면 닫힘"
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
