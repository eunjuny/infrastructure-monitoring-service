import { afterEach, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ResourceDiagnosticsPanel from '../src/components/ResourceDiagnosticsPanel.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
function panel(type: 'CLUSTER' | 'NODE' | 'NAMESPACE' | 'POD', attributes: Record<string, unknown>) {
  const wrapper = mount(ResourceDiagnosticsPanel, { props: { type, attributes } })
  wrappers.push(wrapper)
  return wrapper
}
it('shows whole-cluster ready nodes and problem pods rather than loaded row counts', () => {
  const wrapper = panel('CLUSTER', { nodeCount: 10, readyNodeCount: 8, podCount: 10000, problemPodCount: 2345, diagnosticSource: 'MOCK' })
  expect(wrapper.text()).toContain('전체 하위 리소스 기준')
  expect(wrapper.findAll('.diagnostic-summary dd').map(row => row.text())).toEqual(['8 / 10', '2345 / 10000'])
})
it('shows namespace error and not-ready counts separately, including real zero', () => {
  const wrapper = panel('NAMESPACE', { podCount: 100, errorPodCount: 0, notReadyPodCount: 30 })
  expect(wrapper.findAll('.diagnostic-summary dd').map(row => row.text())).toEqual(['100', '0', '30'])
})
it('renders node condition booleans and capacity units clearly', () => {
  const wrapper = panel('NODE', { ready: false, memoryPressure: false, diskPressure: true, cpuCapacityCores: 16, memoryCapacityMiB: 32768, conditionReason: 'KubeletNotReady' })
  expect(wrapper.text()).toContain('NotReady')
  expect(wrapper.text()).toContain('MemoryPressure없음')
  expect(wrapper.text()).toContain('DiskPressure감지')
  expect(wrapper.text()).toContain('16 코어')
  expect(wrapper.text()).toContain('32 GiB')
})
it('separates Running phase from CrashLoopBackOff and termination cause', () => {
  const wrapper = panel('POD', { phase: 'Running', containerCount: 2, readyContainerCount: 0, containerState: 'Waiting', failureReason: 'CrashLoopBackOff', lastTerminationReason: 'OOMKilled', image: 'registry.example.invalid/mock/worker:1.0.0', workloadKind: 'Deployment', workloadName: 'mock-worker-01', restartCount: 8 })
  for (const value of ['Running', '0 / 2', 'Waiting', 'CrashLoopBackOff', 'OOMKilled', 'Deployment', 'mock-worker-01', '8 회']) expect(wrapper.text()).toContain(value)
})
it('does not turn missing diagnostics into healthy or zero values and supports old details', () => {
  expect(panel('NODE', {}).find('section').exists()).toBe(false)
  const wrapper = panel('POD', { restartCount: 1 })
  expect(wrapper.text()).toContain('1 회')
  expect(wrapper.text()).not.toContain('Ready')
})
it('renders untrusted image or workload as text, never HTML', () => {
  const wrapper = panel('POD', { image: '<img src=x onerror=alert(1)>', workloadName: '<script>bad()</script>', failureReason: null })
  expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
  expect(wrapper.text()).toContain('현재 문제 사유없음')
})
