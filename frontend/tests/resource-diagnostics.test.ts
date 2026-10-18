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
it('labels stored CPU and memory requests distinctly without claiming an aggregate', () => {
  const wrapper = panel('NODE', { cpuRequestedCores: 7.2, memoryRequestedMiB: 8192, diagnosticSource: 'MOCK' })
  expect(wrapper.text()).toContain('Pod CPU 요청량')
  expect(wrapper.text()).toContain('Pod 메모리 요청량')
  expect(wrapper.text()).not.toMatch(/Mock|시나리오/)
  expect(wrapper.text()).toContain('7.2 코어')
  expect(wrapper.text()).toContain('8.0 GiB')
  expect(wrapper.text()).not.toContain('Pod 요청 합계')
})
it('converts fractional measured memory usage without discarding the value as a missing count', () => {
  const wrapper = panel('NODE', { cpuUsageCores: 4.8, memoryUsageMiB: 6553.6 })
  expect(wrapper.text()).toContain('4.8 코어')
  expect(wrapper.text()).toContain('6.4 GiB')
  expect(wrapper.text()).not.toContain('—')
})
it.each([Number.NaN, Number.POSITIVE_INFINITY, -1])('does not present invalid memory usage %s as a real value', value => {
  const wrapper = panel('NODE', { memoryUsageMiB: value })
  expect(wrapper.text()).toContain('—')
  expect(wrapper.text()).not.toContain('GiB')
})
it('shows GPU properties without data-origin banners', () => {
  const wrapper = panel('NODE', { diagnosticSource: 'MOCK', gpuDevices: [{ name: 'gpu-0', model: 'NVIDIA A100', memoryMiB: 40960, utilizationPercent: 65, temperatureCelsius: 60, allocatedPods: 2 }] })
  expect(wrapper.text()).toContain('GPU 장치')
  expect(wrapper.text()).toContain('65%')
  expect(wrapper.text()).not.toMatch(/Mock|생성 데이터|시나리오/)
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
