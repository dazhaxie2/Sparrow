/**
 * 可关闭遮罩的点击/拖选区分。
 *
 * <p>问题:弹窗遮罩用 @click.self 关闭,但用户长按拖选弹窗内文字时,mouseup 可能落在遮罩上,
 * 浏览器合成 click,target=遮罩,@click.self 误判为"点遮罩关闭",把窗口关掉,选中的内容丢失。</p>
 *
 * <p>修法:只有 mousedown <b>和</b> mouseup 都发生在遮罩本身(target===currentTarget)时,
 * 才认为是真实意图的"点遮罩关闭"。mousedown 从弹窗内容上开始(拖选)的,即使 mouseup 落在遮罩上也不关闭。</p>
 *
 * <p>用法:在遮罩元素上绑定 onMaskMousedown / onMaskMouseup:
 * <pre>
 * &lt;div class="modal" @mousedown="dismiss.onMaskMousedown" @mouseup="dismiss.onMaskMouseup"&gt; ... &lt;/div&gt;
 * const dismiss = useDismissableOverlay(() => emit('close'))
 * </pre></p>
 */
export function useDismissableOverlay(onDismiss: () => void) {
  let downOnMask = false

  function onMaskMousedown(event: MouseEvent) {
    // mousedown 落在遮罩本身(而非内部弹窗)才标记;从弹窗内容开始的拖选不标记。
    downOnMask = event.target === event.currentTarget
  }

  function onMaskMouseup(event: MouseEvent) {
    // 仅当 mousedown 和 mouseup 都在遮罩本身时才关闭(真实点击遮罩)。
    if (downOnMask && event.target === event.currentTarget) {
      onDismiss()
    }
    downOnMask = false
  }

  return { onMaskMousedown, onMaskMouseup }
}
