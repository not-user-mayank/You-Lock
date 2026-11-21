package com.example.applock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class PatternInputView(context: Context) : View(context) {
    var onPatternComplete: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val nodes = mutableListOf<Int>()
    private var pointer = PointF()
    private var isDrawing = false
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF176B50.toInt()
        strokeWidth = 4f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB6C2BB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF176B50.toInt()
        style = Paint.Style.FILL
    }
    private val touchRadius = 30f * density
    private val dotRadius = 8f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centers = (0..8).map(::nodeCenter)

        for (index in 1 until nodes.size) {
            val from = centers[nodes[index - 1]]
            val to = centers[nodes[index]]
            canvas.drawLine(from.x, from.y, to.x, to.y, linePaint)
        }
        if (isDrawing && nodes.isNotEmpty()) {
            val last = centers[nodes.last()]
            canvas.drawLine(last.x, last.y, pointer.x, pointer.y, linePaint)
        }

        centers.forEachIndexed { index, center ->
            val paint = if (index in nodes) selectedPaint else idlePaint
            val radius = if (index in nodes) dotRadius * 1.7f else dotRadius
            canvas.drawCircle(center.x, center.y, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                nodes.clear()
                isDrawing = true
                pointer.set(event.x, event.y)
                addNodeAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pointer.set(event.x, event.y)
                addNodeAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pointer.set(event.x, event.y)
                addNodeAt(event.x, event.y)
                isDrawing = false
                invalidate()
                onPatternComplete?.invoke(nodes.joinToString(","))
                nodes.clear()
                postInvalidateDelayed(180)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                nodes.clear()
                isDrawing = false
                invalidate()
                return true
            }
        }
        return true
    }

    private fun addNodeAt(x: Float, y: Float) {
        val centers = (0..8).map(::nodeCenter)
        val nearest = centers.indices.minByOrNull { index ->
            hypot(centers[index].x - x, centers[index].y - y)
        } ?: return
        val center = centers[nearest]
        if (hypot(center.x - x, center.y - y) <= touchRadius && nearest !in nodes) {
            nodes.add(nearest)
        }
    }

    private fun nodeCenter(index: Int): PointF {
        val side = minOf(width, height).toFloat()
        val cell = side / 3f
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val column = index % 3
        val row = index / 3
        return PointF(left + cell * (column + 0.5f), top + cell * (row + 0.5f))
    }
}
