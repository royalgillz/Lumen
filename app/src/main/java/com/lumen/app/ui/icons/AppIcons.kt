package com.lumen.app.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lumen.app.R

@Composable
fun PrivacyIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.5f, h * 0.08f)
                lineTo(w * 0.82f, h * 0.2f)
                lineTo(w * 0.82f, h * 0.52f)
                cubicTo(w * 0.82f, h * 0.72f, w * 0.68f, h * 0.88f, w * 0.5f, h * 0.95f)
                cubicTo(w * 0.32f, h * 0.88f, w * 0.18f, h * 0.72f, w * 0.18f, h * 0.52f)
                lineTo(w * 0.18f, h * 0.2f)
                close()
            },
            color = color,
            style = Stroke(width = 2.4f),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.34f, h * 0.53f),
            end = Offset(w * 0.47f, h * 0.68f),
            strokeWidth = 2.4f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(w * 0.47f, h * 0.68f),
            end = Offset(w * 0.7f, h * 0.4f),
            strokeWidth = 2.4f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun FolderIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.08f, h * 0.27f),
            size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.62f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            style = Stroke(width = 2.4f),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.2f, h * 0.27f),
            end = Offset(w * 0.38f, h * 0.12f),
            strokeWidth = 2.4f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun SearchDocIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.12f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.78f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            style = Stroke(width = 2.2f),
        )
        drawCircle(
            color = color,
            radius = w * 0.16f,
            center = Offset(w * 0.72f, h * 0.58f),
            style = Stroke(width = 2.2f),
        )
        drawLine(
            color = color,
            start = Offset(w * 0.82f, h * 0.68f),
            end = Offset(w * 0.94f, h * 0.82f),
            strokeWidth = 2.2f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun TrashIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.2f, h * 0.22f), Offset(w * 0.8f, h * 0.22f), 2.2f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.34f, h * 0.14f), Offset(w * 0.66f, h * 0.14f), 2.2f, StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.28f, h * 0.26f),
            size = androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.58f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            style = Stroke(width = 2.2f),
        )
    }
}

@Composable
fun LumenBrandIcon(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.lumen_icon_512),
        contentDescription = "Lumen logo",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
