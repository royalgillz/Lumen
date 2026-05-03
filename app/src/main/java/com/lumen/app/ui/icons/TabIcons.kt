package com.lumen.app.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SearchTabIcon(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        drawCircle(color = color, radius = size.minDimension * 0.32f, style = Stroke(width = 2.6f))
        drawLine(
            color = color,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.9f, size.height * 0.9f),
            strokeWidth = 2.6f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun LibraryTabIcon(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.1f, size.height * 0.24f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.8f, size.height * 0.6f),
            cornerRadius = CornerRadius(5f, 5f),
            style = Stroke(width = 2.6f),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.2f, size.height * 0.12f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.16f),
            cornerRadius = CornerRadius(4f, 4f),
        )
    }
}

@Composable
fun SettingsTabIcon(color: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        drawCircle(color = color, radius = size.minDimension * 0.13f)
        drawCircle(color = color, radius = size.minDimension * 0.37f, style = Stroke(width = 2.6f))
        val radius = size.minDimension * 0.43f
        for (i in 0..7) {
            val angle = (Math.PI * i / 4).toFloat()
            val x = center.x + kotlin.math.cos(angle) * radius
            val y = center.y + kotlin.math.sin(angle) * radius
            drawCircle(color = color, radius = 1.7f, center = Offset(x, y))
        }
    }
}

@Composable
fun LumenGlyphIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        drawRoundRect(
            color = color.copy(alpha = 0.18f),
            topLeft = Offset(size.width * 0.05f, size.height * 0.05f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.9f, size.height * 0.9f),
            cornerRadius = CornerRadius(6f, 6f),
            style = Stroke(width = 2.2f),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, size.height * 0.2f),
            end = Offset(size.width * 0.3f, size.height * 0.78f),
            strokeWidth = 2.8f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, size.height * 0.78f),
            end = Offset(size.width * 0.62f, size.height * 0.78f),
            strokeWidth = 2.8f,
            cap = StrokeCap.Round,
        )
        drawCircle(
            color = color,
            radius = size.minDimension * 0.16f,
            center = Offset(size.width * 0.68f, size.height * 0.46f),
            style = Stroke(width = 2.4f),
        )
    }
}
