package com.upcpocket.app.ui.todo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.upcpocket.app.todo.data.TodoTask
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DRAG_HOLD_TIMEOUT_MS = 150L

internal data class TodoSwapCandidate(
    val id: String,
    val lazyIndex: Int,
    val offset: Int,
    val size: Int,
)

/**
 * 计算拖动项在同分组内是否越过相邻项的换位阈值。
 *
 * 返回 `-1` 表示与前一项交换，`1` 表示与后一项交换，`0` 表示保持当前位置。
 * 校验 `lazyIndex` 顺序以等待 LazyColumn 完成上次换位的重新布局，防止同帧重复换位。
 */
internal fun findTodoSwapTarget(
    draggedTop: Float,
    dragged: TodoSwapCandidate,
    prev: TodoSwapCandidate?,
    next: TodoSwapCandidate?,
    allowSwapUp: Boolean,
    allowSwapDown: Boolean,
): Int {
    if (allowSwapUp && prev != null && prev.lazyIndex < dragged.lazyIndex) {
        val prevBottom = prev.offset + prev.size
        val threshold = minOf(prev.size * 0.5f, dragged.size * 0.6f)
        if (draggedTop < prevBottom - threshold) return -1
    }
    if (allowSwapDown && next != null && next.lazyIndex > dragged.lazyIndex) {
        val threshold = minOf(next.size * 0.5f, dragged.size * 0.6f)
        if (draggedTop + dragged.size > next.offset + threshold) return 1
    }
    return 0
}

internal fun computeAutoScrollStep(
    fingerYInViewport: Float,
    viewportHeight: Float,
    edgeThresholdPx: Float,
    maxStepPx: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
): Float {
    if (viewportHeight <= 0f || edgeThresholdPx <= 0f) return 0f
    return when {
        fingerYInViewport < edgeThresholdPx && canScrollBackward -> {
            val ratio = ((edgeThresholdPx - fingerYInViewport) / edgeThresholdPx).coerceIn(0f, 1f)
            -maxStepPx * ratio
        }
        fingerYInViewport > viewportHeight - edgeThresholdPx && canScrollForward -> {
            val ratio = ((fingerYInViewport - (viewportHeight - edgeThresholdPx)) / edgeThresholdPx)
                .coerceIn(0f, 1f)
            maxStepPx * ratio
        }
        else -> 0f
    }
}

internal class LayoutCoordsRef {
    var coords: LayoutCoordinates? = null
}

@Stable
internal class TodoDragReorderState(
    val lazyListState: LazyListState,
    private val scope: CoroutineScope,
    private val haptic: HapticFeedback,
    private val edgeThresholdPx: Float,
    private val maxAutoScrollStepPx: Float,
    private val reverseHysteresisPx: Float,
    private val onCommitGroupOrder: () -> (List<String>) -> Unit,
) {
    val listCoordsRef = LayoutCoordsRef()

    var localOrder: List<TodoTask>? by mutableStateOf(null)
        private set

    var draggingTaskId: String? by mutableStateOf(null)
        private set

    var liftedTaskId: String? by mutableStateOf(null)
        private set

    var settlingTaskId: String? by mutableStateOf(null)
        private set

    private val desiredItemTop = mutableFloatStateOf(0f)
    val settlingOffsetY = Animatable(0f)
    val dragLiftProgress = Animatable(0f)

    private var externalIncomplete: List<TodoTask> = emptyList()
    private var grabOffsetInItem: Float = 0f
    private var currentFingerYInList: Float = 0f
    private var draggedGroupPinned: Boolean = false
    private var reordered: Boolean = false
    private var lastSwapDirection: Int = 0
    private var lastSwapFingerY: Float = 0f
    private var autoScrollJob: Job? = null
    private var liftGeneration: Int = 0
    private var settleGeneration: Int = 0

    fun updateExternalSnapshot(incomplete: List<TodoTask>) {
        externalIncomplete = incomplete
    }

    fun reconcileExternal(incomplete: List<TodoTask>) {
        externalIncomplete = incomplete
        if (draggingTaskId != null) return
        val pending = localOrder ?: return
        val sameOrder = incomplete.size == pending.size &&
            incomplete.indices.all { i ->
                incomplete[i].id == pending[i].id && incomplete[i].pinned == pending[i].pinned
            }
        val sameMembership = incomplete.size == pending.size &&
            incomplete.mapTo(HashSet()) { it.id to it.pinned } ==
            pending.mapTo(HashSet()) { it.id to it.pinned }
        if (sameOrder || !sameMembership) {
            localOrder = null
        }
    }

    fun rollbackLocalOrder() {
        if (draggingTaskId == null) {
            localOrder = null
        }
    }

    fun isActiveOrSettling(taskId: String): Boolean =
        draggingTaskId == taskId || settlingTaskId == taskId || liftedTaskId == taskId

    fun translationYFor(taskId: String): Float {
        if (draggingTaskId == taskId) {
            val info = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == taskId }
                ?: return 0f
            return desiredItemTop.floatValue - info.offset.toFloat()
        }
        if (settlingTaskId == taskId) {
            return settlingOffsetY.value
        }
        return 0f
    }

    fun liftProgressFor(taskId: String): Float =
        if (liftedTaskId == taskId || draggingTaskId == taskId) dragLiftProgress.value else 0f

    fun startDrag(taskId: String, fingerYInList: Float): Boolean {
        if (draggingTaskId != null) return false
        val current = localOrder ?: externalIncomplete
        val target = current.firstOrNull { it.id == taskId } ?: return false
        if (current.count { it.pinned == target.pinned } <= 1) return false
        val itemInfo = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == taskId }
            ?: return false

        grabOffsetInItem = fingerYInList - itemInfo.offset.toFloat()
        currentFingerYInList = fingerYInList
        desiredItemTop.floatValue = itemInfo.offset.toFloat()
        draggedGroupPinned = target.pinned
        reordered = false
        lastSwapDirection = 0
        lastSwapFingerY = fingerYInList
        val sameLiftedItem = liftedTaskId == taskId
        settleGeneration++
        settlingTaskId = null
        draggingTaskId = taskId
        liftedTaskId = taskId
        localOrder = current

        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        liftGeneration++
        scope.launch {
            if (!sameLiftedItem) {
                dragLiftProgress.snapTo(0f)
            }
            dragLiftProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
            )
        }

        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && draggingTaskId == taskId) {
                withFrameMillis { }
                if (draggingTaskId != taskId) break
                val viewportHeight = listCoordsRef.coords
                    ?.takeIf { it.isAttached }
                    ?.size
                    ?.height
                    ?.toFloat()
                    ?: continue
                val step = computeAutoScrollStep(
                    fingerYInViewport = currentFingerYInList,
                    viewportHeight = viewportHeight,
                    edgeThresholdPx = edgeThresholdPx,
                    maxStepPx = maxAutoScrollStepPx,
                    canScrollBackward = lazyListState.canScrollBackward,
                    canScrollForward = lazyListState.canScrollForward,
                )
                if (step != 0f) {
                    val consumed = lazyListState.scrollBy(step)
                    if (consumed != 0f) {
                        checkSwap(taskId)
                    }
                }
            }
        }
        return true
    }

    fun updateDrag(fingerYInList: Float) {
        val activeId = draggingTaskId ?: return
        currentFingerYInList = fingerYInList
        desiredItemTop.floatValue = fingerYInList - grabOffsetInItem
        checkSwap(activeId)
    }

    fun finishDrag() {
        val activeId = draggingTaskId ?: return
        autoScrollJob?.cancel()
        autoScrollJob = null
        val finalOffset = translationYFor(activeId)
        val committedOrder = localOrder
        val didReorder = reordered
        val pinnedGroup = draggedGroupPinned
        draggingTaskId = null

        val liftToken = ++liftGeneration
        scope.launch {
            try {
                dragLiftProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                )
            } finally {
                if (liftGeneration == liftToken && draggingTaskId != activeId) {
                    liftedTaskId = null
                }
            }
        }

        if (abs(finalOffset) > 0.5f) {
            val settleToken = ++settleGeneration
            settlingTaskId = activeId
            scope.launch {
                try {
                    settlingOffsetY.snapTo(finalOffset)
                    settlingOffsetY.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
                    )
                } finally {
                    if (settleGeneration == settleToken) {
                        settlingTaskId = null
                    }
                }
            }
        }

        if (didReorder && committedOrder != null) {
            val groupIds = committedOrder.filter { it.pinned == pinnedGroup }.map { it.id }
            val externalGroupIds = externalIncomplete.filter { it.pinned == pinnedGroup }.map { it.id }
            if (groupIds != externalGroupIds) {
                onCommitGroupOrder()(groupIds)
                return
            }
        }
        localOrder = null
    }

    fun moveByAccessibility(taskId: String, delta: Int): Boolean {
        if (draggingTaskId != null) return false
        val current = localOrder ?: externalIncomplete
        val fromIndex = current.indexOfFirst { it.id == taskId }
        if (fromIndex !in current.indices) return false
        val toIndex = fromIndex + delta
        if (toIndex !in current.indices) return false
        val task = current[fromIndex]
        if (current[toIndex].pinned != task.pinned) return false
        val updated = current.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        localOrder = updated
        val groupIds = updated.filter { it.pinned == task.pinned }.map { it.id }
        onCommitGroupOrder()(groupIds)
        return true
    }

    private fun checkSwap(activeId: String) {
        val working = localOrder ?: return
        val currentIndex = working.indexOfFirst { it.id == activeId }
        if (currentIndex !in working.indices) return
        val visible = lazyListState.layoutInfo.visibleItemsInfo
        val draggedInfo = visible.firstOrNull { it.key == activeId } ?: return
        val draggedCandidate = TodoSwapCandidate(
            id = activeId,
            lazyIndex = draggedInfo.index,
            offset = draggedInfo.offset,
            size = draggedInfo.size,
        )
        val prevTask = working.getOrNull(currentIndex - 1)?.takeIf { it.pinned == draggedGroupPinned }
        val nextTask = working.getOrNull(currentIndex + 1)?.takeIf { it.pinned == draggedGroupPinned }
        val prevCandidate = prevTask?.let { task ->
            visible.firstOrNull { it.key == task.id }?.let {
                TodoSwapCandidate(task.id, it.index, it.offset, it.size)
            }
        }
        val nextCandidate = nextTask?.let { task ->
            visible.firstOrNull { it.key == task.id }?.let {
                TodoSwapCandidate(task.id, it.index, it.offset, it.size)
            }
        }
        val allowUp = lastSwapDirection != 1 ||
            currentFingerYInList < lastSwapFingerY - reverseHysteresisPx
        val allowDown = lastSwapDirection != -1 ||
            currentFingerYInList > lastSwapFingerY + reverseHysteresisPx
        val direction = findTodoSwapTarget(
            draggedTop = desiredItemTop.floatValue,
            dragged = draggedCandidate,
            prev = prevCandidate,
            next = nextCandidate,
            allowSwapUp = allowUp,
            allowSwapDown = allowDown,
        )
        if (direction == 0) return

        val targetIndex = currentIndex + direction
        val targetLazyIndex = if (direction < 0) {
            prevCandidate!!.lazyIndex
        } else {
            nextCandidate!!.lazyIndex
        }
        val firstIndex = lazyListState.firstVisibleItemIndex
        val firstOffset = lazyListState.firstVisibleItemScrollOffset

        val nextList = working.toMutableList().apply {
            val moved = removeAt(currentIndex)
            add(targetIndex, moved)
        }
        localOrder = nextList
        if (draggedCandidate.lazyIndex == firstIndex || targetLazyIndex == firstIndex) {
            lazyListState.requestScrollToItem(firstIndex, firstOffset)
        }
        lastSwapDirection = direction
        lastSwapFingerY = currentFingerYInList
        reordered = true
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

@Composable
internal fun rememberTodoDragReorderState(
    lazyListState: LazyListState,
    onCommitGroupOrder: (List<String>) -> Unit,
): TodoDragReorderState {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestCommit by rememberUpdatedState(onCommitGroupOrder)
    val edgeThresholdPx = with(density) { 64.dp.toPx() }
    val maxAutoScrollStepPx = with(density) { 14.dp.toPx() }
    val reverseHysteresisPx = with(density) { 16.dp.toPx() }
    return remember(lazyListState, scope, haptic, edgeThresholdPx, maxAutoScrollStepPx, reverseHysteresisPx) {
        TodoDragReorderState(
            lazyListState = lazyListState,
            scope = scope,
            haptic = haptic,
            edgeThresholdPx = edgeThresholdPx,
            maxAutoScrollStepPx = maxAutoScrollStepPx,
            reverseHysteresisPx = reverseHysteresisPx,
            onCommitGroupOrder = { latestCommit },
        )
    }
}

private enum class DragActivation {
    HELD,
    VERTICAL_DRAG,
    CANCELLED,
}

internal fun Modifier.todoDragHandle(
    taskId: String,
    canDrag: Boolean,
    reorderState: TodoDragReorderState,
    handleCoordsRef: LayoutCoordsRef,
): Modifier = this
    .onPlaced { handleCoordsRef.coords = it }
    .pointerInput(taskId, canDrag, reorderState) {
        if (!canDrag) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId = down.id
            val downPos = down.position
            val verticalTriggerPx = (viewConfiguration.touchSlop * 0.5f).coerceAtLeast(4f)
            val horizontalCancelPx = viewConfiguration.touchSlop
            var activationPos = downPos

            val activation = withTimeoutOrNull(DRAG_HOLD_TIMEOUT_MS) {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    var pressedCount = 0
                    var activeChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    for (i in event.changes.indices) {
                        val change = event.changes[i]
                        if (change.pressed) pressedCount++
                        if (change.id == pointerId) activeChange = change
                    }
                    val change = activeChange
                    if (change == null || !change.pressed || pressedCount > 1) {
                        return@withTimeoutOrNull DragActivation.CANCELLED
                    }
                    val dx = change.position.x - downPos.x
                    val dy = change.position.y - downPos.y
                    if (abs(dy) >= verticalTriggerPx && abs(dy) >= abs(dx)) {
                        change.consume()
                        activationPos = change.position
                        return@withTimeoutOrNull DragActivation.VERTICAL_DRAG
                    }
                    if (abs(dx) > horizontalCancelPx && abs(dx) > abs(dy)) {
                        return@withTimeoutOrNull DragActivation.CANCELLED
                    }
                }
            } ?: DragActivation.HELD

            if (activation == DragActivation.CANCELLED) return@awaitEachGesture

            val initialFingerY = resolveFingerYInList(
                handleCoordsRef.coords,
                reorderState.listCoordsRef.coords,
                downPos,
            ) ?: return@awaitEachGesture
            if (!reorderState.startDrag(taskId, initialFingerY)) return@awaitEachGesture

            if (activation == DragActivation.VERTICAL_DRAG) {
                resolveFingerYInList(
                    handleCoordsRef.coords,
                    reorderState.listCoordsRef.coords,
                    activationPos,
                )?.let(reorderState::updateDrag)
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    change.consume()
                    resolveFingerYInList(
                        handleCoordsRef.coords,
                        reorderState.listCoordsRef.coords,
                        change.position,
                    )?.let(reorderState::updateDrag)
                    if (!change.pressed || event.type == PointerEventType.Release ||
                        event.changes.all { !it.pressed }
                    ) {
                        break
                    }
                }
            } finally {
                reorderState.finishDrag()
            }
        }
    }

private fun resolveFingerYInList(
    handleCoords: LayoutCoordinates?,
    listCoords: LayoutCoordinates?,
    localPosition: Offset,
): Float? {
    val hc = handleCoords?.takeIf { it.isAttached } ?: return null
    val lc = listCoords?.takeIf { it.isAttached } ?: return null
    return lc.localPositionOf(hc, localPosition).y
}
