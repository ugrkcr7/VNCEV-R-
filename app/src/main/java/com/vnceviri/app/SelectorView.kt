package com.vnceviri.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Tam ekran görünüm: kullanıcı parmağıyla çevrilecek yazı alanını çizer. */
class SelectorView(ctx: Context, private val onDone: (RectF?) -> Unit) : View(ctx) {

    private var x0 = 0f
    private var y0 = 0f
    private var x1 = 0f
    private var y1 = 0f
    private var drawing = false

    private val fill = Paint().apply {
        color = Color.argb(70, 0, 160, 255)
        style = Paint.Style.FILL
    }
    private val line = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val txt = Paint().apply {
        color = Color.WHITE
        textSize = 46f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private fun rect(): RectF =
        RectF(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.argb(110, 0, 0, 0))
        if (!drawing) {
            c.drawText("Çevrilecek yazı alanını parmağınla çiz", width / 2f, height / 2f, txt)
        } else {
            val r = rect()
            c.drawRect(r, fill)
            c.drawRect(r, line)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; x1 = x0; y1 = y0
                drawing = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                x1 = e.x; y1 = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                x1 = e.x; y1 = e.y
                onDone(rect())
            }
            MotionEvent.ACTION_CANCEL -> onDone(null)
        }
        return true
    }
}
