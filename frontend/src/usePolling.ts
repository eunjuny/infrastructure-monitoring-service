import { computed, ref } from 'vue'

/** Fixed-delay polling: one request group at a time; hidden/offline tabs stop all polling. */
export function usePolling(refresh: () => Promise<boolean>, ready: () => boolean, cancel: () => void, interval = 5000) {
  const enabled = ref(true), running = ref(false), failures = ref(0), paused = ref(false)
  const delay = computed(() => Math.min(interval * 2 ** failures.value, 60000))
  let timer: ReturnType<typeof setTimeout> | undefined, started = false, generation = 0
  const available = () => document.visibilityState !== 'hidden' && navigator.onLine
  function clear() { clearTimeout(timer); timer = undefined }
  function schedule(wait = delay.value) {
    clear(); paused.value = !available()
    if (started && enabled.value && !paused.value) timer = setTimeout(tick, wait)
  }
  async function tick() {
    timer = undefined
    if (!started || !enabled.value || !available()) { schedule(); return }
    if (running.value || !ready()) { schedule(500); return }
    const token = generation
    running.value = true
    let success = false
    try { success = await refresh() } catch { success = false }
    finally {
      running.value = false
      if (token === generation) {
        failures.value = success ? 0 : Math.min(failures.value + 1, 4)
        schedule()
      } else schedule()
    }
  }
  function environmentChanged() {
    if (!available()) {
      clear(); paused.value = true; generation++
      if (running.value) cancel()
    } else schedule()
  }
  function setEnabled(value: boolean) {
    enabled.value = value; generation++; clear()
    if (!value && running.value) cancel()
    if (value) { failures.value = 0; schedule() }
  }
  function start() {
    if (started) return
    started = true
    document.addEventListener('visibilitychange', environmentChanged)
    window.addEventListener('offline', environmentChanged)
    window.addEventListener('online', environmentChanged)
    schedule()
  }
  function dispose() {
    started = false; generation++; clear()
    if (running.value) cancel()
    document.removeEventListener('visibilitychange', environmentChanged)
    window.removeEventListener('offline', environmentChanged)
    window.removeEventListener('online', environmentChanged)
  }
  return { enabled, running, paused, failures, delay, start, dispose, setEnabled }
}
