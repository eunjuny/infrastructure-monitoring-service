import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import OperationsAlerts from '../src/components/OperationsAlerts.vue'
import { api, type AlertSnapshot } from '../src/api'
import { session } from '../src/session'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); Object.assign(session, { enabled: false, name: '', administrator: false, clusters: [] }) })
const snapshot = (): AlertSnapshot => ({ available: true, source: 'Prometheus', fetchedAt: '2026-10-15T00:00:00Z', message: '실제 경보',
  rules: ['inactive', 'pending', 'firing'].map((state, index) => ({ name: `rule-${index}`, title: `운영 조건 ${index}`, severity: 'warning',
    state: state as 'inactive' | 'pending' | 'firing', activeAt: index ? '2026-10-15T00:00:00Z' : null, value: index ? 3 : null,
    condition: '1분 지속 조건', response: '관리 포트를 확인합니다.' })) })

it('shows actual rule states, start time and response guidance', async () => {
  vi.spyOn(api, 'alerts').mockResolvedValue(snapshot())
  const wrapper = mount(OperationsAlerts); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.findAll('.alert-rules > li')).toHaveLength(3)
  expect(wrapper.text()).toContain('조건 미충족')
  expect(wrapper.text()).toContain('지속 여부 확인 중')
  expect(wrapper.text()).toContain('경보 발생 · 주의')
  expect(wrapper.text()).toContain('조건 시작')
  expect(wrapper.text()).toContain('관리 포트를 확인합니다.')
  expect(wrapper.text()).not.toContain('실제 경보')
  expect((wrapper.get('.operations-guidance').element as HTMLDetailsElement).open).toBe(false)
  expect(wrapper.get('.operations-guidance').text()).toContain('백엔드 중단')
})

it('does not present unavailable rules as no alerts', async () => {
  vi.spyOn(api, 'alerts').mockResolvedValue({ ...snapshot(), available: false, rules: [], message: '경보 상태 확인 불가' })
  const wrapper = mount(OperationsAlerts); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.find('[role="alert"]').text()).toContain('확인 불가')
  expect(wrapper.find('.alert-rules').exists()).toBe(false)
})

it('removes previous status when refresh cannot reach the API', async () => {
  vi.spyOn(api, 'alerts').mockResolvedValueOnce(snapshot()).mockRejectedValueOnce(new Error('offline'))
  const wrapper = mount(OperationsAlerts); wrappers.push(wrapper); await flushPromises()
  await wrapper.get('button').trigger('click'); await flushPromises()
  expect(wrapper.find('[role="alert"]').text()).toContain('확인 불가')
  expect(wrapper.find('.alert-rules').exists()).toBe(false)
})

it('renders descriptions as text and cancels requests on unmount', async () => {
  const data = snapshot(); data.rules[0]!.response = '<img src=x onerror=alert(1)>'
  const request = vi.spyOn(api, 'alerts').mockResolvedValue(data)
  const wrapper = mount(OperationsAlerts); await flushPromises()
  expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.text()).toContain('<img')
  const signal = request.mock.calls[0]![0]!
  wrapper.unmount()
  expect(signal.aborted).toBe(true)
})

it('opens the ADMIN mail settings from beside refresh and closes the dialog', async () => {
  Object.assign(session, { enabled: true, administrator: true })
  vi.spyOn(api, 'alerts').mockResolvedValue(snapshot())
  vi.spyOn(api, 'alertEmail').mockResolvedValue({ available: true, recipient: '', enabled: false, smtpConfigured: true })
  const wrapper = mount(OperationsAlerts); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.findAll('.alert-actions button').map(button => button.text())).toEqual(['관리자 메일', '경보 새로고침'])
  await wrapper.get('button[aria-haspopup="dialog"]').trigger('click'); await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('발생·해소 알림 메일 받기')
  await wrapper.get('button[aria-label="메일 설정 닫기"]').trigger('click')
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
})
