package com.thelightphone.sdk.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SCROLLBAR_WIDTH_UNITS = 2f
private const val SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS = 1f
private const val MIN_THUMB_FRACTION = 0.1f
private const val MAX_THUMB_FRACTION = 0.85f

enum class LightScrollBarPosition {
    Outside,

    Inside,
}

private data class LightScrollBarGeometry(
    val trackWidthPx: Float,
    val trackHeightPx: Float,
    val touchWidthPx: Float,
    val contentScrollOffsetPx: Float,
    val maxContentScrollOffsetPx: Float,
) {
    private val contentHeightPx = trackHeightPx + maxContentScrollOffsetPx
    private val visibleContentFraction = trackHeightPx / contentHeightPx
    private val contentScrollFraction = (contentScrollOffsetPx / maxContentScrollOffsetPx).coerceIn(0f, 1f)
    private val touchLeftPx = (trackWidthPx - touchWidthPx) / 2f
    private val touchRightPx = touchLeftPx + touchWidthPx

    val thumbHeightPx = trackHeightPx * visibleContentFraction.coerceIn(MIN_THUMB_FRACTION, MAX_THUMB_FRACTION)
    val maxThumbOffsetPx = trackHeightPx - thumbHeightPx
    val thumbOffsetPx = contentScrollFraction * maxThumbOffsetPx

    fun containsTouchX(xPx: Float): Boolean =
        xPx in touchLeftPx..touchRightPx

    fun containsThumb(xPx: Float, yPx: Float): Boolean =
        containsTouchX(xPx) &&
            yPx >= thumbOffsetPx &&
            yPx <= thumbOffsetPx + thumbHeightPx

    fun contentScrollOffsetToPlaceThumbTopAt(thumbTopPx: Float): Float {
        val fraction = (thumbTopPx / maxThumbOffsetPx).coerceIn(0f, 1f)
        return fraction * maxContentScrollOffsetPx
    }
}

fun scrollBarGutterUnits(position: LightScrollBarPosition): Float = when (position) {
    LightScrollBarPosition.Outside -> SCROLLBAR_WIDTH_UNITS
    LightScrollBarPosition.Inside -> 0f
}

fun scrollViewContentWidthUnits(totalWidthUnits: Float, position: LightScrollBarPosition): Float =
    totalWidthUnits - scrollBarGutterUnits(position)

@Composable
fun LightScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrollOffsetPx by remember { derivedStateOf { scrollState.value.toFloat() } }
    val showScrollBar = scrollState.maxValue > 0
    val contentPaddingEnd = scrollBarGutterUnits(scrollBarPosition)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = contentPaddingEnd.gridUnitsAsDp())
                .verticalScroll(scrollState),
            content = content,
        )
        if (showScrollBar) {
            val verticalPadding = if (scrollBarPosition == LightScrollBarPosition.Inside) {
                SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS.gridUnitsAsDp()
            } else {
                0.dp
            }
            LightScrollBar(
                contentScrollOffsetPx = scrollOffsetPx,
                maxContentScrollOffsetPx = scrollState.maxValue.toFloat(),
                onScrollTo = { target ->
                    scope.launch { scrollState.scrollTo(target.roundToInt()) }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = verticalPadding),
            )
        }
    }
}

@Composable
fun LightLazyScrollView(
    modifier: Modifier = Modifier,
    scrollBarPosition: LightScrollBarPosition = LightScrollBarPosition.Outside,
    listState: LazyListState = rememberLazyListState(),
    uniformItemHeightGridUnits: Float,
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { uniformItemHeightGridUnits.gridUnitsAsDp().toPx() }

    val scrollMetrics by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val itemCount = layoutInfo.totalItemsCount
            val viewportHeightPx = layoutInfo.viewportSize.height.toFloat()
            val totalContentPx = itemCount * itemHeightPx
            val maxScrollPx = (totalContentPx - viewportHeightPx).coerceAtLeast(0f)
            val scrollPx = (
                listState.firstVisibleItemIndex * itemHeightPx +
                    listState.firstVisibleItemScrollOffset
                ).coerceAtMost(maxScrollPx)
            scrollPx to maxScrollPx
        }
    }
    val scrollPx = scrollMetrics.first
    val maxScrollPx = scrollMetrics.second
    val showScrollBar = maxScrollPx > 0f
    val contentPaddingEnd = when {
        !showScrollBar -> 0f
        scrollBarPosition == LightScrollBarPosition.Outside -> SCROLLBAR_WIDTH_UNITS
        else -> 0f
    }

    fun scrollToOffsetPx(targetPx: Float) {
        if (itemHeightPx <= 0f) return
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount == 0) return
        val clamped = targetPx.coerceIn(0f, maxScrollPx)
        val index = (clamped / itemHeightPx).toInt().coerceIn(0, itemCount - 1)
        val offset = (clamped - index * itemHeightPx).roundToInt()
        scope.launch { listState.scrollToItem(index, offset) }
    }

    if (scrollBarPosition == LightScrollBarPosition.Inside) {
        Box(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                content = content,
            )
            if (showScrollBar) {
                LightScrollBar(
                    contentScrollOffsetPx = scrollPx,
                    maxContentScrollOffsetPx = maxScrollPx,
                    onScrollTo = ::scrollToOffsetPx,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(
                            vertical = SCROLLBAR_INSIDE_VERTICAL_PADDING_UNITS.gridUnitsAsDp(),
                        ),
                )
            }
        }
    } else {
        Row(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = contentPaddingEnd.gridUnitsAsDp()),
                content = content,
            )
            if (showScrollBar) {
                LightScrollBar(
                    contentScrollOffsetPx = scrollPx,
                    maxContentScrollOffsetPx = maxScrollPx,
                    onScrollTo = ::scrollToOffsetPx,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun LightScrollBar(
    contentScrollOffsetPx: Float,
    maxContentScrollOffsetPx: Float,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = LightThemeTokens.colors.content
    val density = LocalDensity.current
    val trackWidth = SCROLLBAR_WIDTH_UNITS.gridUnitsAsDp()
    val railWidth = 1.dp
    val thumbWidth = 5.dp
    val touchWidth = thumbWidth * 6

    BoxWithConstraints(
        modifier = modifier.width(trackWidth),
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        if (trackHeightPx <= 0f) return@BoxWithConstraints

        val geometry = LightScrollBarGeometry(
            trackWidthPx = with(density) { trackWidth.toPx() },
            trackHeightPx = trackHeightPx,
            touchWidthPx = with(density) { touchWidth.toPx() },
            contentScrollOffsetPx = contentScrollOffsetPx,
            maxContentScrollOffsetPx = maxContentScrollOffsetPx,
        )
        val thumbOffsetDp = with(density) { geometry.thumbOffsetPx.toDp() }
        val thumbHeightDp = with(density) { geometry.thumbHeightPx.toDp() }
        val currentOnScrollTo by rememberUpdatedState(onScrollTo)
        val currentGeometry by rememberUpdatedState(geometry)

        fun handleTrackTap(xPx: Float, yPx: Float) {
            val geometry = currentGeometry
            if (!geometry.containsTouchX(xPx)) return
            if (geometry.containsThumb(xPx, yPx)) return

            val targetThumbTopPx = yPx - geometry.thumbHeightPx / 2f
            currentOnScrollTo(geometry.contentScrollOffsetToPlaceThumbTopAt(targetThumbTopPx))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startGeometry = currentGeometry
                        if (!startGeometry.containsThumb(down.position.x, down.position.y)) {
                            return@awaitEachGesture
                        }

                        down.consume()
                        val dragStartThumbOffsetPx = startGeometry.thumbOffsetPx
                        var dragAmountPx = 0f

                        drag(down.id) { change ->
                            change.consume()
                            val geometry = currentGeometry

                            dragAmountPx += change.position.y - change.previousPosition.y
                            val newThumbTop = (dragStartThumbOffsetPx + dragAmountPx)
                                .coerceIn(0f, geometry.maxThumbOffsetPx)
                            currentOnScrollTo(geometry.contentScrollOffsetToPlaceThumbTopAt(newThumbTop))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        handleTrackTap(offset.x, offset.y)
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .background(barColor),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffsetDp)
                    .width(trackWidth)
                    .height(thumbHeightDp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(thumbWidth)
                        .fillMaxHeight()
                        .background(barColor),
                )
            }
        }
    }
}

@Preview(widthDp = 1080 / 3, heightDp = 1240 / 3, showBackground = true)
@Composable
private fun PreviewLightScrollViewDark() {
    LightTheme(colors = LightThemeColors.Dark) {
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .background(color = LightThemeTokens.colors.background)
                .padding(
                    top = 1f.gridUnitsAsDp(),
                    start = 1f.gridUnitsAsDp(),
                    bottom = 1f.gridUnitsAsDp(),
                ),
            ) {
            repeat(24) { index ->
                LightText(
                    text = "Scrollable row ${index + 1}",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}
