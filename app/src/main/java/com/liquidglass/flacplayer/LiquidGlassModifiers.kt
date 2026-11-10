package com.liquidglass.flacplayer

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.intellij.lang.annotations.Language

/**
 * Shader AGSL nativo para Android 13 / 14+ que genera una onda liquida organica
 * con reflejos especulares de cristal y refraccion cromatica suave.
 */
@Language("AGSL")
const val LIQUID_AURA_SHADER = """
    uniform float2 resolution;
    uniform float time;
    uniform float intensity;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / resolution.xy;
        float2 p = uv * 2.0 - 1.0;
        p.x *= resolution.x / max(resolution.y, 1.0);

        float wave1 = sin(p.x * 3.2 + time * 1.1) * cos(p.y * 2.7 - time * 0.9);
        float wave2 = sin((p.x + p.y) * 4.0 - time * 1.4) * 0.5;
        float liquid = (wave1 + wave2) * 0.5;

        // Brillo especular en bordes (efecto bisel de lente)
        float edgeDist = length(uv - 0.5) * 2.0;
        float rim = smoothstep(0.45, 1.05, edgeDist) * 0.28;

        half3 glassTint = half3(0.65, 0.85, 1.0) * half( clamp(liquid * 0.15 + 0.12, 0.0, 0.4) );
        half3 prismHighlight = half3(0.95, 0.98, 1.0) * half(rim * intensity);

        half alpha = half(clamp(0.08 + rim * 0.35 + abs(liquid) * 0.06, 0.05, 0.42));
        return half4(glassTint + prismHighlight, alpha);
    }
"""

@Composable
fun rememberLiquidTime(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "liquidTime")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 62.83f, // 20 * PI para ciclo continuo suave
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 45000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "timeUniform"
    )
    return time
}

/**
 * Modificador que aplica el estilo Liquid Glass:
 * 1. Superficie translucida con gradiente diagonal de refraccion
 * 2. Shader AGSL en tiempo real (Android 13/14) que simula el movimiento liquido y aberracion de lente
 * 3. Bisel luminoso (borde de cristal biselado con luz superior izquierda y reflejo inferior)
 */
@Composable
fun Modifier.liquidGlassPanel(
    cornerRadius: Dp = 28.dp,
    glassOpacity: Float = 0.16f,
    borderOpacity: Float = 0.48f,
    shaderIntensity: Float = 1.0f
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    val time = rememberLiquidTime()
    val agslShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(LIQUID_AURA_SHADER)
        } else null
    }

    return this
        .clip(shape)
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = glassOpacity + 0.10f),
                    Color(0xFFD8ECFF).copy(alpha = glassOpacity * 0.55f),
                    Color.White.copy(alpha = glassOpacity * 0.85f)
                ),
                start = Offset(0f, 0f),
                end = Offset(900f, 900f)
            ),
            shape = shape
        )
        .drawWithCache {
            onDrawBehind {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && agslShader != null) {
                    agslShader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
                    agslShader.setFloatUniform("time", time)
                    agslShader.setFloatUniform("intensity", shaderIntensity)
                    drawRect(brush = androidx.compose.ui.graphics.ShaderBrush(agslShader))
                }
            }
        }
        .border(
            width = 1.35.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = borderOpacity + 0.25f),
                    Color(0xFF88D4FF).copy(alpha = borderOpacity * 0.35f),
                    Color.White.copy(alpha = 0.08f),
                    Color(0xFFB5E2FF).copy(alpha = borderOpacity * 0.65f)
                ),
                start = Offset(0f, 0f),
                end = Offset(600f, 700f)
            ),
            shape = shape
        )
}

/**
 * Desenfoque Gaussiano de hardware nativo de Android 12/13/14 para la caratula de fondo
 */
fun Modifier.hardwareBlur(radiusPx: Float): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(
                radiusPx,
                radiusPx,
                android.graphics.Shader.TileMode.MIRROR
            ).asComposeRenderEffect()
        }
    } else {
        this
    }
}
