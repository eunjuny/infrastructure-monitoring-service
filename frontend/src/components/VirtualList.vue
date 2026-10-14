<script setup lang="ts" generic="T">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { virtualRange } from '../virtual'
const props = withDefaults(defineProps<{ items: T[]; keys: string[]; rowHeight?: number; label: string }>(), { rowHeight: 52 })
defineSlots<{ default(props: { item: T; index: number }): unknown }>()
const emit = defineEmits<{ end: [] }>()
const viewport = ref<HTMLElement | null>(null), scrollTop = ref(0), height = ref(520)
const range = computed(() => virtualRange(props.items.length, scrollTop.value, height.value, props.rowHeight))
let observer: ResizeObserver | undefined, frame = 0
function onScroll() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0; scrollTop.value = viewport.value?.scrollTop ?? 0
    if (scrollTop.value + height.value >= props.items.length * props.rowHeight - props.rowHeight * 6) emit('end')
  })
}
function reset() {
  if (viewport.value && (scrollTop.value !== 0 || frame)) viewport.value.scrollTop = 0
  cancelAnimationFrame(frame); frame = 0; scrollTop.value = 0
}
function scrollToIndex(index: number) {
  const next = Math.max(0, Math.min(index, props.items.length - 1) * props.rowHeight)
  if (viewport.value) viewport.value.scrollTop = next
  scrollTop.value = viewport.value?.scrollTop ?? next
}
function discardRows(count: number) {
  const next = Math.max(0, scrollTop.value - count * props.rowHeight)
  if (viewport.value) viewport.value.scrollTop = next
  scrollTop.value = next
}
watch(() => props.items.length, count => {
  if (scrollTop.value > Math.max(0, count * props.rowHeight - height.value)) {
    scrollTop.value = Math.max(0, count * props.rowHeight - height.value)
    if (viewport.value) viewport.value.scrollTop = scrollTop.value
  }
})
onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(entries => { height.value = entries[0]?.contentRect.height || 520 })
    if (viewport.value) observer.observe(viewport.value)
  } else height.value = viewport.value?.clientHeight || 520
})
onBeforeUnmount(() => { observer?.disconnect(); cancelAnimationFrame(frame) })
defineExpose({ reset, discardRows, scrollToIndex })
</script>
<template>
  <div ref="viewport" class="virtual-viewport" role="region" tabindex="0" :aria-label="label" @scroll="onScroll">
    <div class="virtual-spacer" :style="{ height: `${range.total}px` }">
      <div class="virtual-window" :style="{ transform: `translateY(${range.top}px)` }">
        <div v-for="(_, index) in items.slice(range.start, range.end)" :key="keys[range.start + index]" class="virtual-row" :style="{ height: `${rowHeight}px` }">
          <slot :item="items[range.start + index]" :index="range.start + index" />
        </div>
      </div>
    </div>
  </div>
</template>
