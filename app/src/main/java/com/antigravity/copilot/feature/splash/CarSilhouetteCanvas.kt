package com.antigravity.copilot.feature.splash

import com.antigravity.copilot.core.designsystem.tokens.BrandPalette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

private class CanvasGeometryCache {
    var width: Float = -1f
    var height: Float = -1f
    var cx: Float = 0f
    var groundY: Float = 0f
    var carLength: Float = 0f
    var carHeight: Float = 0f
    var x0: Float = 0f
    var x1: Float = 0f
    var flareBrush: Brush? = null
}

@Composable
fun CarSilhouetteCanvas(
    trailsProgress: Float,
    silhouetteProgress: Float,
    reflectionAlpha: Float,
    glowIntensity: Float,
    modifier: Modifier = Modifier
) {
    val carRooflinePath = remember { Path() }
    val carUnderbodyPath = remember { Path() }
    val carWaistlinePath = remember { Path() }
    val reflectionRooflinePath = remember { Path() }
    val reflectionUnderbodyPath = remember { Path() }
    val leftTrailPath = remember { Path() }
    val rightTrailPath = remember { Path() }
    val rooflineMeasure = remember { PathMeasure() }
    val underbodyMeasure = remember { PathMeasure() }
    val waistlineMeasure = remember { PathMeasure() }
    val leftTrailMeasure = remember { PathMeasure() }
    val rightTrailMeasure = remember { PathMeasure() }
    val segmentRoofline = remember { Path() }
    val segmentUnderbody = remember { Path() }
    val segmentWaistline = remember { Path() }
    val segmentLeftTrail = remember { Path() }
    val segmentRightTrail = remember { Path() }
    val cache = remember { CanvasGeometryCache() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        if (width != cache.width || height != cache.height) {
            cache.width = width
            cache.height = height
            val cx = width / 2f
            val groundY = height * 0.42f
            val carLength = min(width * 0.48f, 580f)
            val carHeight = carLength * 0.23f
            val x0 = cx - carLength / 2f
            val x1 = cx + carLength / 2f
            val roofY = groundY - carHeight
            cache.cx = cx
            cache.groundY = groundY
            cache.carLength = carLength
            cache.carHeight = carHeight
            cache.x0 = x0
            cache.x1 = x1
            cache.flareBrush = Brush.radialGradient(
                colors = listOf(
                    Color(0x3000D2FF),
                    Color(0x100A58CA),
                    Color.Transparent
                ),
                center = Offset(cx, groundY - carHeight * 0.5f),
                radius = carLength * 0.75f
            )
            rebuildCarGeometry(
                x0 = x0,
                x1 = x1,
                groundY = groundY,
                roofY = roofY,
                carLength = carLength,
                carHeight = carHeight,
                roofline = carRooflinePath,
                underbody = carUnderbodyPath,
                waistline = carWaistlinePath,
                reflectionRoofline = reflectionRooflinePath,
                reflectionUnderbody = reflectionUnderbodyPath,
                leftTrail = leftTrailPath,
                rightTrail = rightTrailPath,
                canvasWidth = width,
                cx = cx
            )
            rooflineMeasure.setPath(carRooflinePath, forceClosed = false)
            underbodyMeasure.setPath(carUnderbodyPath, forceClosed = false)
            waistlineMeasure.setPath(carWaistlinePath, forceClosed = false)
            leftTrailMeasure.setPath(leftTrailPath, forceClosed = false)
            rightTrailMeasure.setPath(rightTrailPath, forceClosed = false)
        }
        val cx = cache.cx
        val groundY = cache.groundY
        val carLength = cache.carLength
        val carHeight = cache.carHeight
        val x0 = cache.x0
        val x1 = cache.x1
        // 1. Radial Horizon Flare
        if (silhouetteProgress > 0.1f && cache.flareBrush != null) {
            drawCircle(
                brush = cache.flareBrush!!,
                radius = carLength * 0.75f,
                center = Offset(cx, groundY - carHeight * 0.5f)
            )
        }
        // 2. Specular Ground Reflection
        if (reflectionAlpha > 0.01f && silhouetteProgress > 0.05f) {
            val reflOpacity = (reflectionAlpha * 0.32f).coerceIn(0f, 0.32f)
            drawPath(
                path = reflectionRooflinePath,
                color = BrandPalette.CyanBright.copy(alpha = reflOpacity),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = reflectionUnderbodyPath,
                color = BrandPalette.Blue700.copy(alpha = reflOpacity * 0.6f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
            drawLine(
                color = BrandPalette.CyanNeon.copy(alpha = (reflectionAlpha * 0.75f).coerceIn(0f, 0.75f)),
                start = Offset(cx - carLength * 0.65f, groundY),
                end = Offset(cx + carLength * 0.65f, groundY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        // 3. Side Light Trails
        if (trailsProgress > 0.01f) {
            val pathLeft = if (trailsProgress >= 1f) {
                leftTrailPath
            } else {
                segmentLeftTrail.reset()
                leftTrailMeasure.getSegment(0f, leftTrailMeasure.length * trailsProgress, segmentLeftTrail, true)
                segmentLeftTrail
            }
            drawGlowPath(
                path = pathLeft,
                coreColor = BrandPalette.CyanNeon,
                glowColor = BrandPalette.Blue700,
                glowAlpha = glowIntensity,
                coreWidth = 2.dp.toPx(),
                glowWidth = 5.dp.toPx()
            )
            val pathRight = if (trailsProgress >= 1f) {
                rightTrailPath
            } else {
                segmentRightTrail.reset()
                rightTrailMeasure.getSegment(0f, rightTrailMeasure.length * trailsProgress, segmentRightTrail, true)
                segmentRightTrail
            }
            drawGlowPath(
                path = pathRight,
                coreColor = BrandPalette.CyanBright,
                glowColor = BrandPalette.Blue700,
                glowAlpha = glowIntensity,
                coreWidth = 2.dp.toPx(),
                glowWidth = 5.dp.toPx()
            )
        }
        // 4. Car Silhouette
        if (silhouetteProgress > 0.01f) {
            val pathRoof = if (silhouetteProgress >= 1f) {
                carRooflinePath
            } else {
                segmentRoofline.reset()
                rooflineMeasure.getSegment(0f, rooflineMeasure.length * silhouetteProgress, segmentRoofline, true)
                segmentRoofline
            }
            drawGlowPath(
                path = pathRoof,
                coreColor = Color(0xFFFFFFFF),
                glowColor = BrandPalette.CyanBright,
                glowAlpha = glowIntensity,
                coreWidth = 2.dp.toPx(),
                glowWidth = 5.5.dp.toPx()
            )
            val pathUnder = if (silhouetteProgress >= 1f) {
                carUnderbodyPath
            } else {
                segmentUnderbody.reset()
                underbodyMeasure.getSegment(0f, underbodyMeasure.length * silhouetteProgress, segmentUnderbody, true)
                segmentUnderbody
            }
            drawGlowPath(
                path = pathUnder,
                coreColor = BrandPalette.CyanNeon,
                glowColor = BrandPalette.Blue700,
                glowAlpha = glowIntensity * 0.8f,
                coreWidth = 1.6.dp.toPx(),
                glowWidth = 4.dp.toPx()
            )
            val pathWaist = if (silhouetteProgress >= 1f) {
                carWaistlinePath
            } else {
                segmentWaistline.reset()
                waistlineMeasure.getSegment(0f, waistlineMeasure.length * silhouetteProgress, segmentWaistline, true)
                segmentWaistline
            }
            drawGlowPath(
                path = pathWaist,
                coreColor = Color(0xFF80E5FF),
                glowColor = Color(0xFF1E60FF),
                glowAlpha = glowIntensity * 0.6f,
                coreWidth = 1.2.dp.toPx(),
                glowWidth = 3.dp.toPx()
            )
            if (silhouetteProgress > 0.85f) {
                val lightAlpha = ((silhouetteProgress - 0.85f) / 0.15f * glowIntensity).coerceIn(0f, 1f)
                drawLine(
                    color = BrandPalette.CyanNeon.copy(alpha = lightAlpha),
                    start = Offset(x1 - carLength * 0.08f, groundY - carHeight * 0.24f),
                    end = Offset(x1, groundY - carHeight * 0.20f),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFF3B82F6).copy(alpha = lightAlpha),
                    start = Offset(x0, groundY - carHeight * 0.28f),
                    end = Offset(x0 + carLength * 0.06f, groundY - carHeight * 0.30f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun rebuildCarGeometry(
    x0: Float,
    x1: Float,
    groundY: Float,
    roofY: Float,
    carLength: Float,
    carHeight: Float,
    roofline: Path,
    underbody: Path,
    waistline: Path,
    reflectionRoofline: Path,
    reflectionUnderbody: Path,
    leftTrail: Path,
    rightTrail: Path,
    canvasWidth: Float,
    cx: Float
) {
    roofline.reset()
    roofline.moveTo(x0, groundY - carHeight * 0.28f)
    roofline.lineTo(x0 + carLength * 0.05f, groundY - carHeight * 0.32f)
    roofline.cubicTo(
        x0 + carLength * 0.12f, groundY - carHeight * 0.40f,
        x0 + carLength * 0.20f, groundY - carHeight * 0.72f,
        x0 + carLength * 0.34f, roofY + carHeight * 0.08f
    )
    roofline.cubicTo(
        x0 + carLength * 0.42f, roofY,
        x0 + carLength * 0.50f, roofY,
        x0 + carLength * 0.56f, roofY + carHeight * 0.04f
    )
    roofline.cubicTo(
        x0 + carLength * 0.65f, roofY + carHeight * 0.16f,
        x0 + carLength * 0.73f, groundY - carHeight * 0.45f,
        x0 + carLength * 0.80f, groundY - carHeight * 0.40f
    )
    roofline.cubicTo(
        x0 + carLength * 0.88f, groundY - carHeight * 0.36f,
        x0 + carLength * 0.96f, groundY - carHeight * 0.25f,
        x1, groundY - carHeight * 0.20f
    )
    roofline.quadraticBezierTo(
        x1 - carLength * 0.01f, groundY - carHeight * 0.06f,
        x1 - carLength * 0.03f, groundY
    )
    val rScale = 0.28f
    reflectionRoofline.reset()
    reflectionRoofline.moveTo(x0, groundY + carHeight * 0.28f * rScale)
    reflectionRoofline.lineTo(x0 + carLength * 0.05f, groundY + carHeight * 0.32f * rScale)
    reflectionRoofline.cubicTo(
        x0 + carLength * 0.12f, groundY + carHeight * 0.40f * rScale,
        x0 + carLength * 0.20f, groundY + carHeight * 0.72f * rScale,
        x0 + carLength * 0.34f, groundY + (groundY - (roofY + carHeight * 0.08f)) * rScale
    )
    reflectionRoofline.cubicTo(
        x0 + carLength * 0.42f, groundY + carHeight * rScale,
        x0 + carLength * 0.50f, groundY + carHeight * rScale,
        x0 + carLength * 0.56f, groundY + (groundY - (roofY + carHeight * 0.04f)) * rScale
    )
    reflectionRoofline.cubicTo(
        x0 + carLength * 0.65f, groundY + (groundY - (roofY + carHeight * 0.16f)) * rScale,
        x0 + carLength * 0.73f, groundY + carHeight * 0.45f * rScale,
        x0 + carLength * 0.80f, groundY + carHeight * 0.40f * rScale
    )
    reflectionRoofline.cubicTo(
        x0 + carLength * 0.88f, groundY + carHeight * 0.36f * rScale,
        x0 + carLength * 0.96f, groundY + carHeight * 0.25f * rScale,
        x1, groundY + carHeight * 0.20f * rScale
    )
    reflectionRoofline.quadraticBezierTo(
        x1 - carLength * 0.01f, groundY + carHeight * 0.06f * rScale,
        x1 - carLength * 0.03f, groundY
    )
    underbody.reset()
    underbody.moveTo(x1 - carLength * 0.03f, groundY)
    underbody.quadraticBezierTo(
        x1 - carLength * 0.10f, groundY - carHeight * 0.14f,
        x1 - carLength * 0.18f, groundY
    )
    underbody.lineTo(x0 + carLength * 0.24f, groundY)
    underbody.quadraticBezierTo(
        x0 + carLength * 0.16f, groundY - carHeight * 0.14f,
        x0 + carLength * 0.08f, groundY
    )
    underbody.lineTo(x0, groundY - carHeight * 0.05f)
    reflectionUnderbody.reset()
    reflectionUnderbody.moveTo(x1 - carLength * 0.03f, groundY)
    reflectionUnderbody.quadraticBezierTo(
        x1 - carLength * 0.10f, groundY + carHeight * 0.14f * rScale,
        x1 - carLength * 0.18f, groundY
    )
    reflectionUnderbody.lineTo(x0 + carLength * 0.24f, groundY)
    reflectionUnderbody.quadraticBezierTo(
        x0 + carLength * 0.16f, groundY + carHeight * 0.14f * rScale,
        x0 + carLength * 0.08f, groundY
    )
    reflectionUnderbody.lineTo(x0, groundY + carHeight * 0.05f * rScale)
    waistline.reset()
    waistline.moveTo(x0 + carLength * 0.04f, groundY - carHeight * 0.34f)
    waistline.cubicTo(
        x0 + carLength * 0.25f, groundY - carHeight * 0.38f,
        x0 + carLength * 0.65f, groundY - carHeight * 0.32f,
        x1 - carLength * 0.06f, groundY - carHeight * 0.22f
    )
    leftTrail.reset()
    leftTrail.moveTo(-40f, groundY + 28f)
    leftTrail.cubicTo(
        cx * 0.28f, groundY + 34f,
        x0 - 90f, groundY + 12f,
        x0, groundY - carHeight * 0.05f
    )
    rightTrail.reset()
    rightTrail.moveTo(canvasWidth + 40f, groundY - 18f)
    rightTrail.cubicTo(
        canvasWidth - cx * 0.28f, groundY - 14f,
        x1 + 90f, groundY + 8f,
        x1 - carLength * 0.03f, groundY
    )
}

private fun DrawScope.drawGlowPath(
    path: Path,
    coreColor: Color,
    glowColor: Color,
    glowAlpha: Float,
    coreWidth: Float,
    glowWidth: Float
) {
    drawPath(
        path = path,
        color = glowColor.copy(alpha = (0.35f * glowAlpha).coerceIn(0f, 1f)),
        style = Stroke(
            width = glowWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    drawPath(
        path = path,
        color = coreColor,
        style = Stroke(
            width = coreWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}
