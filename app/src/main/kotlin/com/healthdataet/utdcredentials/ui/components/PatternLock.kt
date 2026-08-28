package com.healthdataet.utdcredentials.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot

/**
 * A 3x3 Android-style pattern lock: drag across dots to connect them, lift
 * to finish. Used identically for both setting a new pattern
 * (SecuritySettingsScreen, entered twice to confirm) and verifying one
 * (LockScreen) -- [onPatternComplete] fires with the ordered list of node
 * indices (0-8, left-to-right/top-to-bottom) the instant the finger lifts,
 * whatever that list is (the caller decides what counts as valid/matching,
 * this widget only knows how to capture the gesture).
 */
@Composable
fun PatternLock(modifier: Modifier = Modifier, onPatternComplete: (List<Int>) -> Unit) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf(listOf<Int>()) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant

    fun nodeCenters(size: IntSize): List<Offset> {
        if (size.width == 0 || size.height == 0) return emptyList()
        val cellW = size.width / 3f
        val cellH = size.height / 3f
        val centers = mutableListOf<Offset>()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                centers.add(Offset(cellW * col + cellW / 2f, cellH * row + cellH / 2f))
            }
        }
        return centers
    }

    fun nearestNode(pos: Offset, centers: List<Offset>, touchRadius: Float): Int? {
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        centers.forEachIndexed { idx, c ->
            val d = hypot(pos.x - c.x, pos.y - c.y)
            if (d < touchRadius && d < bestDist) {
                bestDist = d
                best = idx
            }
        }
        return best
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onSizeChanged { canvasSize = it }
            .pointerInput(canvasSize) {
                val centers = nodeCenters(canvasSize)
                val touchRadius = minOf(canvasSize.width, canvasSize.height) / 3f / 2.2f
                detectDragGestures(
                    onDragStart = { offset ->
                        val idx = nearestNode(offset, centers, touchRadius)
                        selected = if (idx != null) listOf(idx) else emptyList()
                        dragPos = offset
                    },
                    onDrag = { change, _ ->
                        dragPos = change.position
                        val idx = nearestNode(change.position, centers, touchRadius)
                        if (idx != null && idx !in selected) {
                            selected = selected + idx
                        }
                    },
                    onDragEnd = {
                        if (selected.isNotEmpty()) onPatternComplete(selected)
                        selected = emptyList()
                        dragPos = null
                    },
                    onDragCancel = {
                        selected = emptyList()
                        dragPos = null
                    }
                )
            }
    ) {
        val centers = nodeCenters(canvasSize)
        val nodeRadius = size.minDimension / 3f / 2.2f * 0.35f

        // Connecting lines between already-selected nodes.
        for (i in 0 until selected.size - 1) {
            drawLine(
                color = primary,
                start = centers[selected[i]],
                end = centers[selected[i + 1]],
                strokeWidth = 8f
            )
        }
        // Line from the last selected node to the current finger position.
        val pos = dragPos
        if (pos != null && selected.isNotEmpty()) {
            drawLine(
                color = primary,
                start = centers[selected.last()],
                end = pos,
                strokeWidth = 8f
            )
        }

        centers.forEachIndexed { idx, c ->
            val isSelected = idx in selected
            drawCircle(
                color = if (isSelected) primary else outline,
                radius = nodeRadius,
                center = c,
                style = if (isSelected) Fill else Stroke(width = 4f)
            )
        }
    }
}
