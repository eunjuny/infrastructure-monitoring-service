<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ name: string }>()
const paths: Record<string, string[]> = {
  CLUSTER: ['M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z'],
  NODE: ['M4 4h16v6H4z M4 14h16v6H4z', 'M7 7h.01 M7 17h.01 M11 7h6 M11 17h6'],
  NAMESPACE: ['M3 7h7l2 2h9v11H3z', 'M3 7V4h7l2 3'],
  POD: ['M12 3l8 4.5v9L12 21l-8-4.5v-9z', 'M4 7.5l8 4.5 8-4.5 M12 12v9'],
  search: ['M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15z M16 16l5 5'],
  refresh: ['M20 7v5h-5 M4 17v-5h5', 'M5 8a8 8 0 0 1 13-3l2 2 M19 16a8 8 0 0 1-13 3l-2-2'],
  close: ['M6 6l12 12 M6 18L18 6'],
  chevron: ['M9 5l7 7-7 7'],
  activity: ['M3 12h4l3-8 4 16 3-8h4'],
  layout: ['M3 4h18v16H3z M9 4v16'],
  layers: ['M12 3L2 8l10 5 10-5z M2 12l10 5 10-5 M2 16l10 5 10-5'],
  arrow: ['M5 12h14 M13 6l6 6-6 6'],
}
const selectedPaths = computed(() => paths[props.name] || paths.POD)
</script>
<template>
  <svg class="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path v-for="(path, index) in selectedPaths" :key="index" :d="path" />
  </svg>
</template>
