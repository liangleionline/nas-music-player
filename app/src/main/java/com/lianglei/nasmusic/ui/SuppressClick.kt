package com.lianglei.nasmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * v1.4.45：屏蔽"向右滑呼出抽屉"时的误点击。
 *
 * 背景：SwipeDrawerLayout 在 dispatchTouchEvent 里"纯观察"（不拦截），抬手时右滑超过
 * 24dp 即打开抽屉；而 Compose 行的标准 clickable 在快速右滑（位移处于点击判定窗口内）
 * 时可能未被取消 → 抽屉打开的同时触发行点击（如误播放歌曲）。
 *
 * 机制：pointerInput 挂在 clickable 之前（外层，先收到事件）：手指从按下点右移超过 ~7dp
 *    （小于 tap slop，先于点击判定）即置 suppressed 标志；onClick 内查标志，若本次手势
 *    发生过右滑则忽略点击并复位。
 *
 * 注意（v1.4.46）：绝不 consume 事件——消费 move 会让 LazyColumn 的滚动检测判定拖动被
 * 中断（上滑滚动时手指轻微右偏即触发），导致列表手感发涩。标志机制已足够拦截误点击：
 * 位移 7~8dp（点击判定窗口）由标志拦截；位移 >tap slop 时 clickable 自身取消点击。
 *
 * 影响范围：仅"右滑"被抑制。轻点（无位移）、左滑、上下滚动列表均不受影响。
 */
fun Modifier.suppressClickOnRightSwipe(onClick: () -> Unit): Modifier = composed {
    val suppressed = remember { mutableStateOf(false) }
    val slopPx = with(LocalDensity.current) { 7.dp.toPx() }
    Modifier
        .pointerInput(slopPx) {
            awaitPointerEventScope {
                var down = false
                var downX = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.pressed) {
                        if (!down) {
                            // 新手势开始：复位抑制标志并记录按下位置
                            down = true
                            downX = change.position.x
                            suppressed.value = false
                        } else if (!suppressed.value && change.position.x - downX > slopPx) {
                            // 右移超过 7dp：判定为"右滑呼出抽屉"手势，抑制本次点击。
                            // 只置标志、不 consume——消费会中断列表滚动拖动
                            suppressed.value = true
                        }
                    } else {
                        down = false
                    }
                }
            }
        }
        .clickable(
            onClick = {
                val wasSuppressed = suppressed.value
                suppressed.value = false
                if (!wasSuppressed) onClick()
            }
        )
}
