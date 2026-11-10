package com.bandkey.app

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlinx.coroutines.delay

object Bk {
    val Background = Color(0xFF05070B)
    val Background2 = Color(0xFF080C12)
    val Surface = Color(0xFF0D131C)
    val Surface2 = Color(0xFF111A26)
    val Field = Color(0xFF080D15)
    val Text = Color(0xFFEDF6FA)
    val Muted = Color(0xFF8A9BAE)
    val Dim = Color(0xFF5A6B7E)
    val Mint = Color(0xFF63F2CF)
    val MintDeep = Color(0xFF2ED3B0)
    val Iris = Color(0xFF8C7BFF)
    val Amber = Color(0xFFFFB86B)
    val Coral = Color(0xFFFF6B81)
    val Line = Color(0x14FFFFFF)
    val LineStrong = Color(0x21FFFFFF)
}

enum class MainTab(val label: String) {
    UNLOCK("解锁"),
    DEVICES("设备"),
    SETTINGS("设置")
}

enum class BkGlyph {
    LOCK,
    PHONE,
    SETTINGS,
    SHIELD,
    CHECK,
    ALERT,
    CHEVRON_RIGHT,
    BACK,
    PLUS,
    CLOSE,
    KEY,
    CLOCK,
    LINK,
    BLUETOOTH,
    WIFI,
    TRASH,
    REFRESH,
    POWER
}

@Composable
fun BandKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Bk.Mint,
            onPrimary = Bk.Background,
            secondary = Bk.Iris,
            onSecondary = Bk.Text,
            background = Bk.Background,
            onBackground = Bk.Text,
            surface = Bk.Surface,
            onSurface = Bk.Text,
            error = Bk.Coral,
            onError = Bk.Background
        ),
        content = content
    )
}

@Composable
fun BkBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Bk.Background, Bk.Background2, Bk.Background)
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Bk.Mint.copy(alpha = 0.075f), Color.Transparent),
                    center = Offset(size.width * 0.82f, size.height * 0.12f),
                    radius = size.minDimension * 0.65f
                ),
                radius = size.minDimension * 0.65f,
                center = Offset(size.width * 0.82f, size.height * 0.12f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Bk.Iris.copy(alpha = 0.055f), Color.Transparent),
                    center = Offset(size.width * 0.08f, size.height * 0.75f),
                    radius = size.minDimension * 0.72f
                ),
                radius = size.minDimension * 0.72f,
                center = Offset(size.width * 0.08f, size.height * 0.75f)
            )
        }
        content()
    }
}

@Composable
fun TechCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Bk.Surface2.copy(alpha = 0.88f), Bk.Surface.copy(alpha = 0.96f))
                )
            )
            .border(1.dp, Bk.Line, shape)
            .padding(padding),
        content = content
    )
}

@Composable
fun StatusPill(
    text: String,
    tone: Color = Bk.Mint,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false
) {
    val motion = motionAllowed()
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status-dot"
    )
    val dotAlpha = if (pulsing && motion) pulse else 1f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.08f))
            .border(1.dp, tone.copy(alpha = 0.24f), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tone.copy(alpha = dotAlpha))
        )
        Text(
            text = text,
            color = tone,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.1.sp
        )
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Bk.Field.copy(alpha = 0.9f))
            .border(1.dp, Bk.Line, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val background by animateColorAsState(
                targetValue = if (selected) Bk.Mint.copy(alpha = 0.12f) else Color.Transparent,
                animationSpec = tween(180),
                label = "segment-bg"
            )
            val content by animateColorAsState(
                targetValue = if (selected) Bk.Mint else Bk.Muted,
                animationSpec = tween(180),
                label = "segment-text"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .border(
                        1.dp,
                        if (selected) Bk.Mint.copy(alpha = 0.28f) else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = content,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TrustDial(
    channelReady: Boolean,
    identityReady: Boolean,
    credentialReady: Boolean,
    busy: Boolean,
    statusLabel: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val motion = motionAllowed()
    val channel by animateFloatAsState(
        targetValue = if (channelReady) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 150f),
        label = "channel-arc"
    )
    val identity by animateFloatAsState(
        targetValue = if (identityReady) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 150f),
        label = "identity-arc"
    )
    val credential by animateFloatAsState(
        targetValue = if (credentialReady) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 150f),
        label = "credential-arc"
    )
    val transition = rememberInfiniteTransition(label = "dial-motion")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dial-rotation"
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dial-breath"
    )
    val readied = channelReady && identityReady && credentialReady

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = size.minDimension
            val stroke = (diameter * 0.012f).coerceAtLeast(2.dp.toPx())
            val arcRadius = diameter * 0.385f
            val arcSide = arcRadius * 2f
            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)

            if (motion && (readied || busy)) {
                drawCircle(
                    color = Bk.Mint.copy(alpha = 0.035f + breath * 0.035f),
                    radius = diameter * (0.465f + breath * 0.012f),
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            repeat(60) { index ->
                val angle = Math.toRadians((index * 6.0) - 90.0)
                val major = index % 5 == 0
                val inner = diameter * if (major) 0.442f else 0.452f
                val outer = diameter * 0.462f
                drawLine(
                    color = Color.White.copy(alpha = if (major) 0.16f else 0.065f),
                    start = Offset(
                        center.x + kotlin.math.cos(angle).toFloat() * inner,
                        center.y + kotlin.math.sin(angle).toFloat() * inner
                    ),
                    end = Offset(
                        center.x + kotlin.math.cos(angle).toFloat() * outer,
                        center.y + kotlin.math.sin(angle).toFloat() * outer
                    ),
                    strokeWidth = if (major) 1.3.dp.toPx() else 0.8.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            val arcs = listOf(
                Triple(-90f, channel, Bk.Mint),
                Triple(30f, identity, Bk.Iris),
                Triple(150f, credential, Bk.Mint.copy(alpha = 0.58f))
            )
            arcs.forEach { (start, progress, color) ->
                drawArc(
                    color = Color.White.copy(alpha = 0.055f),
                    startAngle = start,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = Size(arcSide, arcSide),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (progress > 0.01f) {
                    drawArc(
                        color = color,
                        startAngle = start,
                        sweepAngle = 70f * progress,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = Size(arcSide, arcSide),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }

            if (busy && motion) {
                drawArc(
                    color = Bk.Mint.copy(alpha = 0.8f),
                    startAngle = rotation,
                    sweepAngle = 58f,
                    useCenter = false,
                    topLeft = Offset(center.x - diameter * 0.315f, center.y - diameter * 0.315f),
                    size = Size(diameter * 0.63f, diameter * 0.63f),
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Bk.Surface2.copy(alpha = 0.92f),
                        Bk.Field.copy(alpha = 0.98f)
                    ),
                    center = center,
                    radius = diameter * 0.32f
                ),
                radius = diameter * 0.32f
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.055f),
                radius = diameter * 0.32f,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 58.dp)
        ) {
            Text(
                text = statusLabel,
                color = if (busy) Bk.Amber else if (readied) Bk.Mint else Bk.Iris,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.2.sp,
                maxLines = 1
            )
            Text(
                text = title,
                color = Bk.Text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            Text(
                text = subtitle,
                color = Bk.Muted,
                fontSize = 9.sp,
                lineHeight = 13.sp,
                letterSpacing = 0.7.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

data class RailItem(
    val label: String,
    val value: String,
    val meta: String,
    val tone: Color,
    val pulsing: Boolean = false
)

@Composable
fun StatusRail(
    items: List<RailItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(item.tone.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.pulsing) {
                        val motion = motionAllowed()
                        val transition = rememberInfiniteTransition(label = "rail-${item.label}")
                        val alpha by transition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(850, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "rail-dot"
                        )
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(item.tone.copy(alpha = if (motion) alpha else 1f))
                        )
                    } else {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(item.tone)
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = item.label,
                    color = Bk.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = item.value,
                        color = item.tone,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = item.meta,
                        color = Bk.Dim,
                        fontSize = 9.sp,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun PrimaryAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryText: String? = null,
    busy: Boolean = false,
    glyph: BkGlyph? = BkGlyph.LOCK,
    tone: Color = Bk.Mint
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.52f, stiffness = 760f),
        label = "action-scale"
    )
    val motion = motionAllowed()
    val transition = rememberInfiniteTransition(label = "action-spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "action-rotation"
    )
    val brush = if (enabled || busy) {
        Brush.horizontalGradient(
            listOf(tone, tone.copy(red = (tone.red * 0.8f).coerceAtLeast(0f), alpha = 0.96f))
        )
    } else {
        Brush.horizontalGradient(listOf(Bk.Surface2, Bk.Surface))
    }

    Box(
        modifier = modifier
            .heightIn(min = 58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .border(
                1.dp,
                if (enabled || busy) Color.White.copy(alpha = 0.2f) else Bk.Line,
                RoundedCornerShape(16.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !busy,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (busy) {
                Canvas(Modifier.size(18.dp)) {
                    drawArc(
                        color = Bk.Background.copy(alpha = 0.28f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Bk.Background.copy(alpha = 0.78f),
                        startAngle = if (motion) rotation else -90f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            } else if (glyph != null) {
                BkIcon(
                    glyph = glyph,
                    tint = if (enabled) Bk.Background.copy(alpha = 0.86f) else Bk.Dim,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = text,
                    color = if (enabled || busy) Bk.Background else Bk.Dim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp,
                    maxLines = 1
                )
                if (secondaryText != null) {
                    Text(
                        text = secondaryText,
                        color = if (enabled || busy) {
                            Bk.Background.copy(alpha = 0.56f)
                        } else {
                            Bk.Dim.copy(alpha = 0.72f)
                        },
                        fontSize = 9.sp,
                        letterSpacing = 0.4.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Color = Bk.Muted,
    glyph: BkGlyph? = null
) {
    Row(
        modifier = modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Bk.Surface.copy(alpha = 0.6f))
            .border(1.dp, Bk.Line, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (glyph != null) {
            BkIcon(
                glyph = glyph,
                tint = if (enabled) tone else Bk.Dim,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = if (enabled) tone else Bk.Dim,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun IconAction(
    glyph: BkGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Bk.Muted,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Bk.Surface.copy(alpha = 0.72f))
            .border(1.dp, Bk.Line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BkIcon(
            glyph = glyph,
            tint = if (enabled) tint else Bk.Dim,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun BottomNav(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Bk.Surface.copy(alpha = 0.96f))
            .border(1.dp, Bk.Line, RoundedCornerShape(22.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MainTab.entries.forEach { tab ->
            val active = tab == selected
            val background by animateColorAsState(
                targetValue = if (active) Bk.Mint.copy(alpha = 0.1f) else Color.Transparent,
                animationSpec = tween(180),
                label = "nav-bg"
            )
            val content by animateColorAsState(
                targetValue = if (active) Bk.Mint else Bk.Muted,
                animationSpec = tween(180),
                label = "nav-content"
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(17.dp))
                    .background(background)
                    .border(
                        1.dp,
                        if (active) Bk.Mint.copy(alpha = 0.22f) else Color.Transparent,
                        RoundedCornerShape(17.dp)
                    )
                    .clickable { onSelect(tab) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                BkIcon(
                    glyph = when (tab) {
                        MainTab.UNLOCK -> BkGlyph.LOCK
                        MainTab.DEVICES -> BkGlyph.PHONE
                        MainTab.SETTINGS -> BkGlyph.SETTINGS
                    },
                    tint = content,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = tab.label,
                    color = content,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun TechTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingText: String? = null,
    enabled: Boolean = true
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        targetValue = if (focused) Bk.Mint.copy(alpha = 0.5f) else Bk.Line,
        animationSpec = tween(160),
        label = "field-border"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Bk.Muted,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
            if (trailingText != null) {
                Text(
                    text = trailingText,
                    color = Bk.Dim,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Bk.Field.copy(alpha = 0.96f))
                .border(1.dp, border, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 13.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = if (enabled) Bk.Text else Bk.Dim,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                ),
                cursorBrush = SolidColor(Bk.Mint),
                keyboardOptions = keyboardOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                color = Bk.Dim,
                                fontSize = 13.sp,
                                maxLines = if (singleLine) 1 else 3
                            )
                        }
                        inner()
                    }
                }
            )
        }
    }
}

@Composable
fun SettingCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    statusText: String? = null,
    statusTone: Color = Bk.Mint,
    content: @Composable ColumnScope.() -> Unit
) {
    TechCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Bk.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        color = Bk.Muted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            if (statusText != null) {
                Spacer(Modifier.width(10.dp))
                StatusPill(text = statusText, tone = statusTone)
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Bk.Text,
    mono: Boolean = false,
    valueSize: Int = 11
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Bk.Muted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = valueSize.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(2f)
        )
    }
}

@Composable
fun OtpInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val motion = motionAllowed()
    val transition = rememberInfiniteTransition(label = "otp-cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "otp-cursor-alpha"
    )

    LaunchedEffect(enabled) {
        if (enabled) {
            delay(180)
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            repeat(6) { index ->
                val filled = index < value.length
                val focused = index == value.length.coerceAtMost(5) && enabled
                val scale by animateFloatAsState(
                    targetValue = if (filled) 1f else 0.9f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
                    label = "otp-slot-$index"
                )
                val slotBorder by animateColorAsState(
                    targetValue = when {
                        error -> Bk.Coral.copy(alpha = 0.7f)
                        focused -> Bk.Mint.copy(alpha = 0.64f)
                        filled -> Bk.Mint.copy(alpha = 0.3f)
                        else -> Bk.Line
                    },
                    animationSpec = tween(160),
                    label = "otp-border-$index"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (filled) Bk.Surface2 else Bk.Field)
                        .border(1.dp, slotBorder, RoundedCornerShape(13.dp))
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (filled) {
                        Text(
                            text = value[index].toString(),
                            color = Bk.Text,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    } else if (focused && motion) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Bk.Mint.copy(alpha = cursorAlpha))
                        )
                    }
                }
            }
        }

        BasicTextField(
            value = value,
            onValueChange = {
                if (enabled) {
                    onValueChange(it.filter(Char::isDigit).take(6))
                }
            },
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            modifier = Modifier
                .matchParentSize()
                .alpha(0.01f)
                .focusRequester(focusRequester)
        )
    }
}

@Composable
fun CountdownBar(
    totalSeconds: Int,
    remainingSeconds: Int,
    modifier: Modifier = Modifier
) {
    val ratio = (remainingSeconds.toFloat() / totalSeconds.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animatedRatio by animateFloatAsState(
        targetValue = ratio,
        animationSpec = tween(440, easing = LinearEasing),
        label = "countdown-ratio"
    )
    val urgent = remainingSeconds <= 60
    val tone by animateColorAsState(
        targetValue = if (urgent) Bk.Amber else Bk.Mint,
        animationSpec = tween(240),
        label = "countdown-tone"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (remainingSeconds == 0) "PIN 已过期" else "配对有效期",
                color = if (urgent) Bk.Amber else Bk.Muted,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatDuration(remainingSeconds),
                color = tone,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp
            )
            Text(
                text = " / ${formatDuration(totalSeconds)}",
                color = Bk.Dim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Bk.Line)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedRatio)
                    .height(4.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Bk.MintDeep, tone, Bk.Iris.copy(alpha = 0.9f))
                        )
                    )
            )
        }
    }
}

@Composable
fun SuccessRing(
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
    }
    val motion = motionAllowed()
    val transition = rememberInfiniteTransition(label = "success-breath")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "success-breath-value"
    )

    Canvas(modifier = modifier) {
        val diameter = size.minDimension
        val stroke = 2.dp.toPx()
        if (motion) {
            drawCircle(
                color = Bk.Mint.copy(alpha = 0.025f + breath * 0.035f),
                radius = diameter * (0.46f + breath * 0.02f)
            )
        }
        drawCircle(
            color = Bk.Mint.copy(alpha = 0.08f),
            radius = diameter * 0.39f,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Bk.Mint.copy(alpha = 0.11f),
            radius = diameter * 0.31f
        )
        drawCircle(
            color = Bk.Mint.copy(alpha = 0.22f),
            radius = diameter * 0.31f,
            style = Stroke(width = 1.dp.toPx())
        )
        drawArc(
            color = Bk.Mint,
            startAngle = -90f,
            sweepAngle = 360f * progress.value,
            useCenter = false,
            topLeft = Offset(diameter * 0.16f, diameter * 0.16f),
            size = Size(diameter * 0.68f, diameter * 0.68f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        val checkAlpha = ((progress.value - 0.42f) / 0.58f).coerceIn(0f, 1f)
        if (checkAlpha > 0f) {
            val checkPath = Path().apply {
                moveTo(diameter * 0.365f, diameter * 0.51f)
                lineTo(diameter * 0.465f, diameter * 0.61f)
                lineTo(diameter * 0.65f, diameter * 0.405f)
            }
            drawPath(
                path = checkPath,
                color = Bk.Mint.copy(alpha = checkAlpha),
                style = Stroke(
                    width = 3.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

@Composable
fun GlyphBadge(
    glyph: BkGlyph,
    tone: Color = Bk.Mint,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(
                Brush.linearGradient(
                    listOf(tone.copy(alpha = 0.17f), tone.copy(alpha = 0.045f))
                )
            )
            .border(1.dp, tone.copy(alpha = 0.24f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        BkIcon(
            glyph = glyph,
            tint = tone,
            modifier = Modifier.size(size * 0.48f)
        )
    }
}

@Composable
fun DeviceRow(
    name: String,
    meta: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    trailing: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(if (active) Bk.Surface2.copy(alpha = 0.72f) else Bk.Surface.copy(alpha = 0.42f))
            .border(
                1.dp,
                if (active) Bk.Mint.copy(alpha = 0.18f) else Bk.Line,
                RoundedCornerShape(17.dp)
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphBadge(
            glyph = BkGlyph.PHONE,
            tone = if (active) Bk.Mint else Bk.Muted,
            size = 42.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = Bk.Text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                color = Bk.Dim,
                fontSize = 9.sp,
                maxLines = 1
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = trailing,
                tone = if (active) Bk.Mint else Bk.Muted,
                pulsing = active
            )
        }
    }
}

@Composable
fun StepCard(
    number: Int,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(Bk.Surface2.copy(alpha = 0.56f))
            .border(1.dp, Bk.Line, RoundedCornerShape(15.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(27.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Bk.Mint.copy(alpha = 0.09f))
                .border(1.dp, Bk.Mint.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString().padStart(2, '0'),
                color = Bk.Mint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Bk.Text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                color = Bk.Muted,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = Bk.Dim,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.7.sp,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                text = trailing,
                color = Bk.Dim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun StatusNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = Bk.Muted,
    glyph: BkGlyph? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (glyph != null) {
            BkIcon(
                glyph = glyph,
                tint = tone,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(13.dp)
            )
        }
        Text(
            text = text,
            color = tone,
            fontSize = 9.sp,
            lineHeight = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun BkIcon(
    glyph: BkGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.65.dp
) {
    Canvas(modifier) {
        val unit = size.minDimension / 24f
        val ox = (size.width - unit * 24f) / 2f
        val oy = (size.height - unit * 24f) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * unit, oy + y * unit)
        fun s(value: Float) = value * unit
        val stroke = Stroke(
            width = strokeWidth.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )

        when (glyph) {
            BkGlyph.LOCK -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(5f, 10f),
                    size = Size(s(14f), s(10f)),
                    cornerRadius = CornerRadius(s(2.2f)),
                    style = stroke
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = p(8f, 4f),
                    size = Size(s(8f), s(10f)),
                    style = stroke
                )
                drawCircle(tint, radius = s(1.05f), center = p(12f, 15f))
            }
            BkGlyph.PHONE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = p(7f, 3f),
                    size = Size(s(10f), s(18f)),
                    cornerRadius = CornerRadius(s(2f)),
                    style = stroke
                )
                drawLine(tint, p(10.5f, 17.5f), p(13.5f, 17.5f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.SETTINGS -> {
                drawCircle(tint, radius = s(3f), center = p(12f, 12f), style = stroke)
                drawCircle(tint, radius = s(1.1f), center = p(12f, 12f))
                repeat(8) { index ->
                    val angle = Math.toRadians(index * 45.0)
                    val cx = p(12f, 12f).x
                    val cy = p(12f, 12f).y
                    drawLine(
                        tint,
                        Offset(
                            cx + kotlin.math.cos(angle).toFloat() * s(5.2f),
                            cy + kotlin.math.sin(angle).toFloat() * s(5.2f)
                        ),
                        Offset(
                            cx + kotlin.math.cos(angle).toFloat() * s(7f),
                            cy + kotlin.math.sin(angle).toFloat() * s(7f)
                        ),
                        stroke.width,
                        StrokeCap.Round
                    )
                }
            }
            BkGlyph.SHIELD -> {
                val path = Path().apply {
                    moveTo(p(12f, 2.8f).x, p(12f, 2.8f).y)
                    lineTo(p(19f, 5.8f).x, p(19f, 5.8f).y)
                    lineTo(p(18f, 13.5f).x, p(18f, 13.5f).y)
                    lineTo(p(12f, 21f).x, p(12f, 21f).y)
                    lineTo(p(6f, 13.5f).x, p(6f, 13.5f).y)
                    lineTo(p(5f, 5.8f).x, p(5f, 5.8f).y)
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            BkGlyph.CHECK -> {
                val path = Path().apply {
                    moveTo(p(5f, 12.2f).x, p(5f, 12.2f).y)
                    lineTo(p(9.7f, 17f).x, p(9.7f, 17f).y)
                    lineTo(p(19f, 7f).x, p(19f, 7f).y)
                }
                drawPath(path, tint, style = stroke)
            }
            BkGlyph.ALERT -> {
                val path = Path().apply {
                    moveTo(p(12f, 3f).x, p(12f, 3f).y)
                    lineTo(p(22f, 20.5f).x, p(22f, 20.5f).y)
                    lineTo(p(2f, 20.5f).x, p(2f, 20.5f).y)
                    close()
                }
                drawPath(path, tint, style = stroke)
                drawLine(tint, p(12f, 9f), p(12f, 14f), stroke.width, StrokeCap.Round)
                drawCircle(tint, radius = s(0.9f), center = p(12f, 17.2f))
            }
            BkGlyph.CHEVRON_RIGHT -> {
                val path = Path().apply {
                    moveTo(p(9f, 5f).x, p(9f, 5f).y)
                    lineTo(p(16f, 12f).x, p(16f, 12f).y)
                    lineTo(p(9f, 19f).x, p(9f, 19f).y)
                }
                drawPath(path, tint, style = stroke)
            }
            BkGlyph.BACK -> {
                val path = Path().apply {
                    moveTo(p(15f, 5f).x, p(15f, 5f).y)
                    lineTo(p(8f, 12f).x, p(8f, 12f).y)
                    lineTo(p(15f, 19f).x, p(15f, 19f).y)
                }
                drawPath(path, tint, style = stroke)
            }
            BkGlyph.PLUS -> {
                drawLine(tint, p(12f, 5f), p(12f, 19f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(5f, 12f), p(19f, 12f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.CLOSE -> {
                drawLine(tint, p(6f, 6f), p(18f, 18f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(18f, 6f), p(6f, 18f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.KEY -> {
                drawCircle(tint, radius = s(3.8f), center = p(8.5f, 9f), style = stroke)
                drawLine(tint, p(11.2f, 11.7f), p(20f, 20.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(17f, 17.5f), p(19.5f, 15f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(19f, 19.5f), p(21.5f, 17f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.CLOCK -> {
                drawCircle(tint, radius = s(8f), center = p(12f, 12f), style = stroke)
                drawLine(tint, p(12f, 7f), p(12f, 12f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(12f, 12f), p(15.5f, 14.5f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.LINK -> {
                drawArc(
                    tint,
                    startAngle = 120f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = p(3.5f, 8f),
                    size = Size(s(9f), s(9f)),
                    style = stroke
                )
                drawArc(
                    tint,
                    startAngle = -60f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = p(11.5f, 8f),
                    size = Size(s(9f), s(9f)),
                    style = stroke
                )
                drawLine(tint, p(9f, 12f), p(15f, 12f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.BLUETOOTH -> {
                val path = Path().apply {
                    moveTo(p(12f, 3f).x, p(12f, 3f).y)
                    lineTo(p(12f, 21f).x, p(12f, 21f).y)
                    moveTo(p(12f, 3f).x, p(12f, 3f).y)
                    lineTo(p(17f, 7f).x, p(17f, 7f).y)
                    lineTo(p(7f, 16f).x, p(7f, 16f).y)
                    moveTo(p(12f, 21f).x, p(12f, 21f).y)
                    lineTo(p(17f, 17f).x, p(17f, 17f).y)
                    lineTo(p(7f, 8f).x, p(7f, 8f).y)
                }
                drawPath(path, tint, style = stroke)
            }
            BkGlyph.WIFI -> {
                drawArc(
                    tint, 225f, 90f, false, p(2f, 2f), Size(s(20f), s(20f)),
                    style = stroke
                )
                drawArc(
                    tint, 225f, 90f, false, p(5.5f, 5.5f), Size(s(13f), s(13f)),
                    style = stroke
                )
                drawArc(
                    tint, 225f, 90f, false, p(9f, 9f), Size(s(6f), s(6f)),
                    style = stroke
                )
                drawCircle(tint, radius = s(1.1f), center = p(12f, 18.5f))
            }
            BkGlyph.TRASH -> {
                drawLine(tint, p(4f, 7f), p(20f, 7f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(9f, 4f), p(15f, 4f), stroke.width, StrokeCap.Round)
                drawRoundRect(
                    tint, p(6f, 7f), Size(s(12f), s(13f)), CornerRadius(s(2f)), style = stroke
                )
                drawLine(tint, p(10f, 11f), p(10f, 16.5f), stroke.width, StrokeCap.Round)
                drawLine(tint, p(14f, 11f), p(14f, 16.5f), stroke.width, StrokeCap.Round)
            }
            BkGlyph.REFRESH -> {
                drawArc(
                    tint, -70f, 285f, false, p(3f, 3f), Size(s(18f), s(18f)),
                    style = stroke
                )
                val path = Path().apply {
                    moveTo(p(17.4f, 2.8f).x, p(17.4f, 2.8f).y)
                    lineTo(p(21f, 4.4f).x, p(21f, 4.4f).y)
                    lineTo(p(18.9f, 7.8f).x, p(18.9f, 7.8f).y)
                }
                drawPath(path, tint, style = stroke)
            }
            BkGlyph.POWER -> {
                drawArc(
                    tint, 120f, 300f, false, p(4f, 4f), Size(s(16f), s(16f)),
                    style = stroke
                )
                drawLine(tint, p(12f, 2.5f), p(12f, 11f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

@Composable
private fun motionAllowed(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0.01f
    }
}
