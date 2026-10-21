/* 设计移植自 FlClash (GPL-3.0) lib/ui/navigation_dock.dart — https://github.com/chen08209/FlClash */

package com.chengzi.stayhifi.ui.nav

import android.content.res.Configuration
import android.view.MotionEvent
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** 布局与物理公式，移植自 FlClash `navigation_dock.dart`，纯函数可单测。
 * 公式单位无关：以下常量是原作 dp 基准值，px 调用侧必须按 density 换算后显式传入。 */
internal object DockMath {
    const val BarHeight = 62f
    const val BarPadding = 4f
    const val EdgeMargin = 21f
    const val ShadowRoom = 8f
    const val MaxItemExtent = 86f
    const val MinLensWidth = 84f
    const val FullWidthCount = 5
    const val IconSize = 28f
    const val LabelGap = 2f
    const val LabelInset = 2f
    const val LabelSize = 10f
    const val PressGrowth = 1f / 8f
    const val MaxPressGrowth = 16f
    const val LensGrowth = 8f
    const val LensMagnify = 0.16f
    const val LiftSnap = 0.975f
    const val LiftRelease = 0.04f
    const val JellySpeed = 3f
    const val JellyStretch = 0.2f
    const val JellyBrake = 25f
    const val JellySquash = 0.15f
    const val JellyAspect = 1.4f
    const val Overdrag = 0.35f
    const val PullLimit = 7f / 32f
    const val PullStretch = 0.5f
    const val FlingProjectionSeconds = 0.1f

    /** 透镜矩形，相对 items 行区域左上角。 */
    class LensRect(val left: Float, val top: Float, val width: Float, val height: Float)

    fun rubberBand(overshoot: Float, limit: Float): Float {
        if (limit <= 0f || overshoot == 0f) return 0f
        val pull = 1f - 1f / (abs(overshoot) * 0.55f / limit + 1f)
        return limit * pull * sign(overshoot)
    }

    /** iOS 式间距：静置透镜比 item 宽 2*barPadding，且不小于 minLensWidth。返回 Triple(pitch, lens, inset)。
     * 单位无关：调用方传入与 span 一致的单位（dp 或 px），barPadding/minLensWidth 用同单位。 */
    fun dockLayout(
        span: Float,
        count: Int,
        barPadding: Float = BarPadding,
        minLensWidth: Float = MinLensWidth,
    ): Triple<Float, Float, Float> {
        if (count <= 1) return Triple(span - barPadding * 2, span, barPadding)
        val pitch = (span - barPadding * 2) / count
        if (pitch + barPadding * 2 >= minLensWidth) {
            return Triple(pitch, pitch + barPadding * 2, barPadding)
        }
        val lens = min(minLensWidth, span)
        val closed = (span - lens) / (count - 1)
        return Triple(closed, lens, (lens - closed) / 2)
    }

    fun indexAt(position: Float, lastIndex: Int): Int =
        floor(position + 0.5).toInt().coerceIn(0, lastIndex)

    /** 手指 x（栏坐标系，含 barPadding）→ 透镜位置；两端过界走 rubberBand。 */
    fun positionAt(
        dx: Float,
        pitch: Float,
        inset: Float,
        lastIndex: Int,
        barPadding: Float = BarPadding,
        overdrag: Float = Overdrag,
    ): Float {
        val position = (dx - barPadding - inset) / pitch - 0.5f
        return when {
            position < 0f -> rubberBand(position, overdrag)
            position > lastIndex -> lastIndex + rubberBand(position - lastIndex, overdrag)
            else -> position
        }
    }

    /** fling 最多越过当前项 ±1 个，再 clamp 到范围内。 */
    fun flingIndex(lensTarget: Float, velocityX: Float, pitch: Float, pressed: Int, lastIndex: Int): Int =
        floor(lensTarget + velocityX / pitch * FlingProjectionSeconds + 0.5).toInt()
            .coerceIn(pressed - 1, pressed + 1)
            .coerceIn(0, lastIndex)

    fun lensRect(
        position: Float,
        stretch: Float,
        pitch: Float,
        width: Float,
        height: Float,
        lift: Float,
        inset: Float,
        lensGrowth: Float = LensGrowth,
        jellyAspect: Float = JellyAspect,
    ): LensRect {
        val growth = lensGrowth * 2 * lift
        val lensWidth = (width + growth) * (1 + stretch)
        val lensHeight = (height + growth) * (1 - stretch * jellyAspect)
        return LensRect(
            left = (position + 0.5f) * pitch - lensWidth / 2 + inset,
            top = (height - lensHeight) / 2,
            width = lensWidth,
            height = lensHeight,
        )
    }

    /** Dart `_saturate`: 1 - 2 / (exp(2v) + 1)。 */
    fun saturate(value: Float): Float {
        val e = exp(2.0 * value)
        return (1.0 - 2.0 / (e + 1.0)).toFloat()
    }
}

// 弹簧参数对照裁决表：withDampingRatio 直搬；withDurationAndBounce 按 Flutter 官方公式
// stiffness=(2π/T)²·m、damping=4π(1−bounce)m/T → dampingRatio = 1−bounce（UIWidgets 文档 SpringDescription.withDurationAndBounce）。
private val PressSpring = spring<Float>(dampingRatio = 0.593f, stiffness = 311f)
private val FollowSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 1024f)
private val JellySpring = spring<Float>(dampingRatio = 0.4f, stiffness = 100f)
// travel 400ms/bounce 0.15 → ζ=0.85（原 0.79 系换算表误差，回弹过量）。
private val TravelSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 247f)
private val LiftSpring = spring<Float>(dampingRatio = 1f, stiffness = 632f)
private val TrackSpring = spring<Float>(dampingRatio = 1f, stiffness = 2742f)

/** 超椭圆 path（Lamé n=4 近似）：graphics-shapes smoothing=1 的圆角矩形，即 Flutter RoundedSuperellipseBorder 的视觉等价。 */
private fun superellipsePath(size: Size, radius: Float): Path =
    RoundedPolygon.rectangle(
        width = size.width,
        height = size.height,
        rounding = CornerRounding(radius, smoothing = 1f),
        centerX = size.width / 2f,
        centerY = size.height / 2f,
    ).toPath().asComposePath()

/** 栏体超椭圆形状（半径 = 短边一半，对应原作 AppShape.full）。 */
private val SuperellipseShape = GenericShape { bounds, _ ->
    addPath(superellipsePath(Size(bounds.width, bounds.height), min(bounds.width, bounds.height) / 2f))
}

class DockDestination(val icon: ImageVector, val label: String)

/** 一次手势里的布局参数（px，全 px 单位）。 */
private class DockLayout(
    val pitch: Float,
    val lens: Float,
    val inset: Float,
    val barPadding: Float,
    val barWidth: Float,
    val barHeight: Float,
    val lastIndex: Int,
)

/**
 * 忠实移植原作 `_Spring`（navigation_dock.dart L150-217）：springTo 只换动画对象、
 * 不重启 ticker（即不起协程）。每次 springTo 以当前值/速度重建 TargetBasedAnimation，
 * 由常驻帧循环 onFrame 推进；120Hz Move 事件流下逐帧 retarget 也能立即生效。
 * value/target 用 mutableFloatStateOf：快照可观察，draw 阶段读取兼容。
 *
 * 时钟基准必须始终是帧时钟（原作 _start/_now 都是 ticker 帧时间）：
 * playTime 为负时欠阻尼弹簧解析式（x(-t) 含 e^{+ζω|t|} 项）会反向爆炸，
 * 故所有 playTime 一律钳制 >= 0，springTo 也用 lastFrameNanos 锚定而非 System.nanoTime()。
 */
private class SpringValue(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    var target = initial
        private set
    private var anim: TargetBasedAnimation<Float, AnimationVector1D>? = null
    private var startNanos = 0L

    private fun playTime(nowNanos: Long) = (nowNanos - startNanos).coerceAtLeast(0L)

    val velocity: Float
        get() = anim?.getVelocityVectorFromNanos(playTime(System.nanoTime()))?.value ?: 0f

    fun springTo(target: Float, spec: SpringSpec<Float>, anchorNanos: Long) {
        val velocity = anim?.getVelocityVectorFromNanos(playTime(anchorNanos))?.value ?: 0f
        anim = TargetBasedAnimation(spec, Float.VectorConverter, value, target, velocity)
        this.target = target
        startNanos = anchorNanos
    }

    fun jumpTo(value: Float) {
        anim = null
        this.value = value
        this.target = value
    }

    fun onFrame(nowNanos: Long) {
        val animation = anim ?: return
        value = animation.getValueFromNanos(playTime(nowNanos))
        if (animation.isFinishedFromNanos(playTime(nowNanos))) {
            value = target
            anim = null
        }
    }
}

/**
 * 一组弹簧状态的持有者。对应原作的 `_Spring` 列表：
 * 每个 springTo 保留当前速度、只换目标（等价原作 springTo 的换 SpringSimulation），
 * 推进统一由 NavigationDock 的常驻帧循环驱动，不起任何协程。
 */
private class DockController(
    selectedIndex: Int,
) {
    val lens = SpringValue(selectedIndex.toFloat())
    val lift = SpringValue(0f)
    val swell = SpringValue(0f)
    val stretch = SpringValue(0f)
    val jelly = SpringValue(0f)

    var lensTarget = selectedIndex.toFloat()
        private set
    var pressedIndex: Int? = null
        private set
    var dragging = false
        private set
    var pointerDown = false
        private set
    private var dropping = false
    private var released = false
    private var pressX = 0f

    // 帧时钟锚点（onFrame 更新）：springTo 一律用它锚定 startNanos，
    // 与原作 _Spring 以 ticker 帧时间锚定 _start 一致；首帧前退回 System.nanoTime()。
    private var lastFrameNanos = 0L
    private val anchorNanos: Long
        get() = if (lastFrameNanos != 0L) lastFrameNanos else System.nanoTime()

    fun onFrame(nowNanos: Long) {
        lastFrameNanos = nowNanos
        lens.onFrame(nowNanos)
        lift.onFrame(nowNanos)
        swell.onFrame(nowNanos)
        stretch.onFrame(nowNanos)
        jelly.onFrame(nowNanos)
    }

    fun travel(target: Float) {
        lensTarget = target
        lens.springTo(target, TravelSpring, anchorNanos)
    }

    fun press(x: Float, layout: DockLayout, touchSlop: Float) {
        pointerDown = true
        released = false
        pressX = x
        dragging = false
        dropping = false
        swell.springTo(1f, PressSpring, anchorNanos)
        val index = DockMath.indexAt(
            DockMath.positionAt(x, layout.pitch, layout.inset, layout.lastIndex, barPadding = layout.barPadding),
            layout.lastIndex,
        )
        pressedIndex = index
        lift.springTo(1f, LiftSpring, anchorNanos)
        travel(index.toFloat())
    }

    /** 返回 true 表示已越过拖动阈值（用于消费事件）。 */
    fun slide(x: Float, layout: DockLayout, touchSlop: Float, haptic: () -> Unit): Boolean {
        if (pressedIndex == null) return false
        if (!dragging) {
            if (abs(x - pressX) < touchSlop) return false
            dragging = true
        }
        val overshoot = x - x.coerceIn(0f, layout.barWidth)
        stretch.springTo(
            DockMath.rubberBand(overshoot, layout.barHeight * DockMath.PullLimit),
            TrackSpring,
            anchorNanos,
        )
        val position = DockMath.positionAt(x, layout.pitch, layout.inset, layout.lastIndex, barPadding = layout.barPadding)
        lensTarget = position
        lens.springTo(position, FollowSpring, anchorNanos)
        val index = DockMath.indexAt(position, layout.lastIndex)
        if (index != pressedIndex) {
            haptic()
            pressedIndex = index
        }
        return true
    }

    fun fling(velocityX: Float, layout: DockLayout, haptic: () -> Unit) {
        val pressed = pressedIndex
        if (!dragging || pressed == null) return
        val index = DockMath.flingIndex(lensTarget, velocityX, layout.pitch, pressed, layout.lastIndex)
        if (index != pressed) {
            haptic()
            pressedIndex = index
        }
    }

    fun release(commit: Boolean, selected: Int, onSelected: (Int) -> Unit) {
        pointerDown = false
        released = true
        // 原作 _release：pull 立即归零，swell 不归零——由 _settle 等膨胀升到 LiftSnap 再放下。
        stretch.springTo(0f, PressSpring, anchorNanos)
        val index = pressedIndex ?: return
        pressedIndex = null
        dragging = false
        dropping = true
        travel(if (commit) index.toFloat() else selected.toFloat())
        checkDropLift()
        if (commit && index != selected) onSelected(index)
    }

    /** UIKit 语义：透镜升到顶（>= LiftSnap）且归位到位后才放下。 */
    fun checkDropLift() {
        if (!dropping || pointerDown || lift.value < DockMath.LiftSnap) return
        if (abs(lensTarget - lens.value) > DockMath.LiftRelease) return
        dropping = false
        lift.springTo(0f, LiftSpring, anchorNanos)
    }

    /** 原作 ElasticPress._settle：快速点按松手后膨胀先升满（>= LiftSnap）再弹回，气泡感不被掐断。 */
    fun checkSettleSwell() {
        if (!released || pointerDown || swell.value < DockMath.LiftSnap) return
        released = false
        swell.springTo(0f, PressSpring, anchorNanos)
    }

    fun settleToSelection(selected: Int) {
        if (pressedIndex == null && !pointerDown && lensTarget != selected.toFloat()) {
            travel(selected.toFloat())
        }
    }

    fun updateJelly(target: Float) {
        if (abs(target - jelly.target) > 0.001f) {
            jelly.springTo(target, JellySpring, anchorNanos)
        }
    }
}

/**
 * iOS 风浮动导航 Dock：选中项是一枚在目的地间弹跳归位的透镜。
 * 按下整栏膨胀、透镜升起；拖动透镜跟手、两端过界橡胶带回弹；
 * 松手透镜 travel 弹簧归位并提交选中；fling 最多越过当前项 ±1。
 */
@Composable
fun NavigationDock(
    destinations: List<DockDestination>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val count = destinations.size
    val lastIndex = (count - 1).coerceAtLeast(0)
    val selected = selectedIndex.coerceIn(0, lastIndex)

    val density = LocalDensity.current
    val controller = remember { DockController(selected) }
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelected = rememberUpdatedState(onSelected)
    val colorScheme = MaterialTheme.colorScheme

    val tick: () -> Unit = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    // 外部选中项变化时透镜归位（按下/拖动中不打断）。
    LaunchedEffect(selected) {
        controller.settleToSelection(selected)
    }
    // 常驻帧循环：推进全部 SpringValue（原作 _Spring._tick 的单 ticker 等价）。
    LaunchedEffect(controller) {
        while (true) {
            withFrameNanos { controller.onFrame(it) }
        }
    }
    // lift 升到顶且透镜归位到位后放下透镜；松手后 swell 升满再归零（checkSettleSwell）。
    LaunchedEffect(controller) {
        snapshotFlow { Triple(controller.lift.value, controller.lens.value, controller.swell.value) }
            .collect {
                controller.checkDropLift()
                controller.checkSettleSwell()
            }
    }
    // 果冻变形：透镜速度 → 拉伸，减速度 → 压扁（原作 _wobble；加速度用速度差分近似）。
    LaunchedEffect(controller) {
        var lastVelocity = 0f
        var lastTimeNanos = 0L
        while (true) {
            withFrameNanos { now ->
                val velocity = controller.lens.velocity
                val dt = if (lastTimeNanos == 0L) 0f else (now - lastTimeNanos) / 1_000_000_000f
                lastTimeNanos = now
                val acceleration = if (dt > 0f) (velocity - lastVelocity) / dt else 0f
                lastVelocity = velocity
                val braking = max(0f, -acceleration * sign(velocity))
                val target =
                    DockMath.JellyStretch * DockMath.saturate(abs(velocity) / DockMath.JellySpeed) -
                        DockMath.JellySquash * DockMath.saturate(braking / DockMath.JellyBrake)
                controller.updateJelly(target)
            }
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomMargin = maxOf(DockMath.EdgeMargin.dp, navBottom)
    val maxPressGrowthPx = with(density) { DockMath.MaxPressGrowth.dp.toPx() }
    val isDark = colorScheme.surface.luminance() < 0.5f
    val strength = if (isDark) 3f else 1f
    // material3 1.4 已移除 ColorScheme.shadow（M3 方案里 shadow 恒为黑色）。
    val shadowColor = Color.Black

    Box(
        Modifier
            .fillMaxWidth()
            .height(DockMath.ShadowRoom.dp + DockMath.BarHeight.dp + bottomMargin),
    ) {
        var barModifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = DockMath.EdgeMargin.dp)
            // 底部外边距 = max(21dp, 导航栏 inset)，贴底但留出导航手势区；ShadowRoom 是顶部阴影余量。
            .padding(bottom = bottomMargin)
            .height(DockMath.BarHeight.dp)
            .fillMaxWidth()
        if (count in 1 until DockMath.FullWidthCount) {
            barModifier = barModifier.widthIn(
                max = (count * DockMath.MaxItemExtent + DockMath.BarPadding * 4).dp,
            )
        }

        BoxWithConstraints(
            barModifier
                // iOS 式双软阴影（深色强度 ×3 对照 dockShadows）；clip=false——升起的透镜允许溢出栏体。
                .shadow(
                    8.dp,
                    SuperellipseShape,
                    clip = false,
                    ambientColor = shadowColor.copy(alpha = 0.08f * strength),
                    spotColor = shadowColor.copy(alpha = 0.08f * strength),
                )
                .shadow(
                    1.dp,
                    SuperellipseShape,
                    clip = false,
                    ambientColor = shadowColor.copy(alpha = 0.04f * strength),
                    spotColor = shadowColor.copy(alpha = 0.04f * strength),
                )
                .background(colorScheme.surfaceContainerHigh, SuperellipseShape)
                // Raw pointer 而非 recognizer：拖动没有 arena 要赢，tap 等待会推迟透镜升起。
                .pointerInput(count) {
                    if (count == 0) return@pointerInput
                    // 公式常量是 dp 基准，手势侧一律换算成 px 传入。
                    val paddingPx = with(density) { DockMath.BarPadding.dp.toPx() }
                    val minLensPx = with(density) { DockMath.MinLensWidth.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val span = size.width - paddingPx * 2
                        val (pitch, lensW, inset) = DockMath.dockLayout(
                            span,
                            count,
                            barPadding = paddingPx,
                            minLensWidth = minLensPx,
                        )
                        val layout = DockLayout(
                            pitch = pitch,
                            lens = lensW,
                            inset = inset,
                            barPadding = paddingPx,
                            barWidth = size.width.toFloat(),
                            barHeight = size.height.toFloat(),
                            lastIndex = count - 1,
                        )
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        controller.press(down.position.x, layout, touchSlop)
                        // 只跟踪按下时的主指针：其余指针的 move/release 不得劫持透镜或提前 commit。
                        val pointerId = down.id
                        var active = true
                        while (active) {
                            val event = awaitPointerEvent()
                            val primary = event.changes.find { it.id == pointerId }
                            when (event.type) {
                                PointerEventType.Move -> {
                                    if (primary == null) continue
                                    tracker.addPosition(primary.uptimeMillis, primary.position)
                                    // 注意：系统取消不会走到这里——compose-ui 1.12 把 ACTION_CANCEL
                                    // 合成 pressed=false + changedToUp=true 的 Release 派发（见 Release 分支）。
                                    if (controller.slide(primary.position.x, layout, touchSlop, tick)) {
                                        primary.consume()
                                    }
                                }
                                PointerEventType.Release -> {
                                    // 主指针以外的指针抬起忽略，继续等待主指针。
                                    if (primary == null || primary.pressed) continue
                                    // ACTION_CANCEL（来电/通知栏下拉/系统对话框打断）同样以 Release 到达，
                                    // 必须回查原始 motionEvent：只有真抬起才 fling + 提交选中，
                                    // 取消时透镜回弹不提交（对应原作 commit: event is PointerUpEvent）。
                                    val action = event.motionEvent?.actionMasked
                                    val isUp = action == MotionEvent.ACTION_UP ||
                                        action == MotionEvent.ACTION_POINTER_UP
                                    if (isUp) {
                                        controller.fling(tracker.calculateVelocity().x, layout, tick)
                                    }
                                    controller.release(isUp, currentSelected.value, currentOnSelected.value)
                                    active = false
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                // ElasticPress：整栏 swell + 横向 pull（只影响绘制，命中坐标不变）。
                .graphicsLayer {
                    val swellV = controller.swell.value
                    val pull = controller.stretch.value
                    val maxGrowth = maxPressGrowthPx
                    val growth = min(DockMath.PressGrowth * 2f, maxGrowth / size.maxDimension)
                    val swellScale = 1f + swellV * growth
                    scaleX = swellScale * (1f + abs(pull) / size.width * DockMath.PullStretch)
                    scaleY = swellScale
                    translationX = pull
                },
        ) {
            if (count == 0) return@BoxWithConstraints
            // 绘制侧与手势侧一致：公式常量是 dp 基准，全部换算成 px 传入。
            val barPaddingPx = with(density) { DockMath.BarPadding.dp.toPx() }
            val minLensPx = with(density) { DockMath.MinLensWidth.dp.toPx() }
            val lensGrowthPx = with(density) { DockMath.LensGrowth.dp.toPx() }
            val span = constraints.maxWidth - barPaddingPx * 2
            val (pitch, lensWidth, inset) = DockMath.dockLayout(
                span.toFloat(),
                count,
                barPadding = barPaddingPx,
                minLensWidth = minLensPx,
            )
            val core = Modifier.matchParentSize().padding(DockMath.BarPadding.dp)
            val insetDp = with(density) { inset.toDp() }
            val lensColor = colorScheme.secondaryContainer.copy(alpha = 0.85f)

            // 透镜矩形现算：动画期状态读取全部下沉到绘制阶段（graphicsLayer/draw lambda），
            // 组合期不读任何 Animatable 值，动画帧只触发重绘不触发重组。
            val lensRectNow: (height: Float) -> DockMath.LensRect = { h ->
                DockMath.lensRect(
                    position = controller.lens.value,
                    stretch = controller.jelly.value,
                    pitch = pitch,
                    width = lensWidth,
                    height = h,
                    lift = controller.lift.value,
                    inset = inset,
                    lensGrowth = lensGrowthPx,
                )
            }
            val lensPath: (DockMath.LensRect) -> Path = { r ->
                superellipsePath(Size(r.width, r.height), r.height / 2f).apply {
                    translate(Offset(r.left, r.top))
                }
            }

            // 透镜底（secondaryContainer alpha 0.85，对照 HCT tone 90/4 裁决）。
            Canvas(core) {
                val r = lensRectNow(size.height)
                drawPath(path = lensPath(r), color = lensColor)
            }
            // 底层 items：原色（onSurfaceVariant）。透镜区域挖空（evenOdd 裁剪），
            // 透镜下内容只由上层绘制（对照原作 _LensTint.paint 的 outside 层）。
            Box(
                core.graphicsLayer {
                    clip = true
                    val r = lensRectNow(size.height)
                    shape = GenericShape { _, _ ->
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addPath(lensPath(r))
                    }
                },
            ) {
                DockItems(
                    destinations = destinations,
                    tint = colorScheme.onSurfaceVariant,
                    inset = insetDp,
                    selectedIndex = selected,
                    onActivate = { currentOnSelected.value(it) },
                    modifier = Modifier.matchParentSize(),
                )
            }
            // 透镜层 items：同一 Row 再画一遍，clip 到透镜矩形、内容色 primary、随 lift 放大。
            val magnify: () -> Float = { 1f + DockMath.LensMagnify * controller.lift.value }
            Box(
                core.graphicsLayer {
                    clip = true
                    val r = lensRectNow(size.height)
                    shape = GenericShape { _, _ -> addPath(lensPath(r)) }
                },
            ) {
                DockItems(
                    destinations = destinations,
                    tint = colorScheme.primary,
                    inset = insetDp,
                    selectedIndex = -1,
                    magnify = magnify,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

@Composable
private fun DockItems(
    destinations: List<DockDestination>,
    tint: Color,
    inset: Dp,
    modifier: Modifier = Modifier,
    selectedIndex: Int = -1,
    onActivate: ((Int) -> Unit)? = null,
    magnify: (() -> Float)? = null,
) {
    Row(modifier.padding(horizontal = inset)) {
        destinations.forEachIndexed { index, destination ->
            val itemModifier = if (magnify != null) {
                Modifier.graphicsLayer {
                    val m = magnify()
                    scaleX = m
                    scaleY = m
                }
            } else {
                Modifier
            }
            Box(
                itemModifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (selectedIndex >= 0) {
                            // button + onTap 语义（纯 semantics 动作，不加 clickable，
                            // 避免与栏级 pointerInput 竞争手势 arena）。
                            Modifier.semantics(mergeDescendants = true) {
                                contentDescription = destination.label
                                selected = index == selectedIndex
                                onClick(label = destination.label) {
                                    onActivate?.invoke(index)
                                    true
                                }
                            }
                        } else {
                            Modifier.clearAndSetSemantics { }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.padding(horizontal = DockMath.LabelInset.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                        modifier = Modifier.size(DockMath.IconSize.dp),
                        tint = tint,
                    )
                    Spacer(Modifier.height(DockMath.LabelGap.dp))
                    Text(
                        text = destination.label,
                        color = tint,
                        fontSize = DockMath.LabelSize.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val previewDestinations = listOf(
    DockDestination(Icons.Filled.Home, "首页"),
    DockDestination(Icons.Filled.Headphones, "设备"),
    DockDestination(Icons.Filled.Settings, "设置"),
)

@Preview(name = "浅色", showBackground = true, backgroundColor = 0xFFF8F9FF)
@Composable
private fun NavigationDockLightPreview() {
    StayHiFiTheme(dynamicColor = false) {
        NavigationDock(previewDestinations, 1, {})
    }
}

@Preview(
    name = "深色",
    showBackground = true,
    backgroundColor = 0xFF111320,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun NavigationDockDarkPreview() {
    StayHiFiTheme(dynamicColor = false, darkTheme = true) {
        NavigationDock(previewDestinations, 1, {})
    }
}
