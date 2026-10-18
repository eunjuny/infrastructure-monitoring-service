import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ScopeFilters from '../src/components/ScopeFilters.vue'
import ResourceEvents from '../src/components/ResourceEvents.vue'
import KubernetesEvents from '../src/components/KubernetesEvents.vue'
import OperationsDashboard from '../src/components/OperationsDashboard.vue'
import { api } from '../src/api'
import { page, resource } from './fixtures'
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
beforeEach(() => { vi.spyOn(api, 'alerts').mockResolvedValue({ available: false, source: 'Prometheus', fetchedAt: '', rules: [], message: '경보 미설정' }) })
it('displays a legacy event placeholder without a fixture banner and preserves other messages', async () => {
  vi.spyOn(api, 'kubernetesEvents').mockResolvedValue([
    { id: 1, type: 'WARNING', reason: 'ResourceDegraded', message: 'Mock resource is in a non-normal state.',
      firstSeenAt: '2026-10-18T00:00:00Z', lastSeenAt: '2026-10-18T00:00:00Z', occurrenceCount: 1 },
    { id: 2, type: 'WARNING', reason: 'BackOff', message: 'Container restart condition detected.',
      firstSeenAt: '2026-10-18T00:00:00Z', lastSeenAt: '2026-10-18T00:00:00Z', occurrenceCount: 2 },
  ])
  const wrapper = mount(KubernetesEvents, { props: { resource: resource() } }); wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.text()).toContain('리소스가 비정상 상태입니다.')
  expect(wrapper.text()).not.toContain('Mock')
  expect(wrapper.text()).toContain('Container restart condition detected.')
})
it('loads scope choices lazily and paginates namespaces without loading all rows', async () => {
  const request=vi.spyOn(api,'list').mockResolvedValueOnce(page([{ resource: resource(2,'CLUSTER'), path: [] }]))
    .mockResolvedValueOnce(page([{ resource: resource(101,'NAMESPACE'), path: [] }],'next'))
  const wrapper=mount(ScopeFilters,{props:{cluster:null,namespace:null}}); wrappers.push(wrapper)
  expect(request).not.toHaveBeenCalled()
  expect(wrapper.find('.scope-options').exists()).toBe(false)
  ;(wrapper.find('details').element as HTMLDetailsElement).open=true
  await wrapper.find('details').trigger('toggle'); await flushPromises()
  await wrapper.find('select').setValue('2')
  expect(wrapper.emitted('change')?.[0]?.[0]).toMatchObject({id:2,type:'CLUSTER'})
  expect(wrapper.findAll('option')).toHaveLength(4)
})
it('sends scope constraints for both browsing and search', async () => {
  const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>page([])}); vi.stubGlobal('fetch',fetch)
  await api.list({type:'POD',q:'worker',status:'ERROR',clusterId:2,namespaceId:101})
  const url=new URL(fetch.mock.calls[0]![0],'http://test.invalid')
  expect(Object.fromEntries(url.searchParams)).toMatchObject({clusterId:'2',namespaceId:'101',q:'worker',status:'ERROR'})
})
it('renders history and retention with safe attribute text', async () => {
  vi.spyOn(api,'events').mockResolvedValue({items:[{id:1,occurredAt:'2026-10-14T00:00:00Z',previousStatus:'NORMAL',nextStatus:'ERROR',source:'MOCK_SIMULATOR',beforeAttributes:{restartCount:1},afterAttributes:{restartCount:2}}],nextBeforeId:null,hasMore:false,retentionHours:24,maximumEvents:20000})
  const wrapper=mount(ResourceEvents,{props:{resource:resource()}}); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.text()).toContain('정상 → 오류'); expect(wrapper.text()).toContain('1 → 2'); expect(wrapper.text()).toContain('20,000')
})
it('renders current event details without a data-origin banner', async () => {
  const request = vi.spyOn(api, 'kubernetesEvents').mockResolvedValue([{ id: 1, type: 'WARNING', reason: 'BackOff',
    message: 'Container restart condition detected.', firstSeenAt: '2026-10-18T00:00:00Z',
    lastSeenAt: '2026-10-18T00:01:00Z', occurrenceCount: 2 }])
  const wrapper = mount(KubernetesEvents, { props: { resource: resource(25) } }); wrappers.push(wrapper); await flushPromises()
  expect(request).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('BackOff')
  expect(wrapper.text()).not.toMatch(/생성한 이벤트|Mock|실제 Kubernetes API/)
  expect(wrapper.text()).toContain('2회')
})
it('uses Prometheus data and does not substitute missing values with zero', async () => {
  vi.spyOn(api,'operations').mockResolvedValue({available:true,source:'Prometheus',fetchedAt:'2026-10-14T00:00:00Z',values:{up:1,heap:1048576,apiP95:null},series:{},message:''})
  const wrapper=mount(OperationsDashboard); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.text()).toContain('Prometheus'); expect(wrapper.text()).toContain('표본 없음'); expect(wrapper.findAll('.operation-cards > div')).toHaveLength(10)
  expect(wrapper.findAll('.operation-cards dd')[0]!.text()).toBe('수집 성공')
  expect(wrapper.findAll('.operation-cards dd')[5]!.text()).toBe('1 MiB')
})
it('separates simulator metrics behind a closed disclosure without removing their real values', async () => {
  vi.spyOn(api, 'operations').mockResolvedValue({ available: true, source: 'Prometheus', fetchedAt: '2026-10-14T00:00:00Z',
    values: { up: 1, mockChanged: 116, mockFailures: 0, mockP95: .025 }, series: {}, message: '' })
  const wrapper = mount(OperationsDashboard); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.find('dl[aria-label="서비스 운영 지표"]').findAll('dd')).toHaveLength(7)
  const disclosure = wrapper.get('.simulator-metrics')
  expect((disclosure.element as HTMLDetailsElement).open).toBe(false)
  expect(disclosure.findAll('dd').map(node => node.text())).toEqual(['116 행', '0 회', '25 ms'])
  ;(disclosure.element as HTMLDetailsElement).open = true
  expect((disclosure.element as HTMLDetailsElement).open).toBe(true)
  expect(wrapper.text()).not.toContain('Mock Node 사용률과 다른 데이터입니다.')
  expect(wrapper.text()).not.toContain('표본이 없으면 0으로 대체하지 않습니다.')
})
it('shows a concise empty chart rather than a fabricated zero axis or missing-value units', async () => {
  vi.spyOn(api, 'operations').mockResolvedValue({ available: true, source: 'Prometheus', fetchedAt: '2026-10-14T00:00:00Z',
    values: { apiP95: null }, series: { apiP95: [{ timestamp: 1791936000, value: null }] }, message: '' })
  const wrapper = mount(OperationsDashboard); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.find('.operation-charts svg').exists()).toBe(false)
  expect(wrapper.find('.operation-charts').text()).toContain('최근 30분간 추이 표본 없음')
  expect(wrapper.findAll('.operation-cards dd')[2]!.text()).toBe('표본 없음')
  expect(wrapper.find('.operation-charts').text()).not.toContain('0–0.0ms')
})
it.each([
  [0, '수집 실패'],
  [null, '표본 없음'],
  [undefined, '표본 없음'],
  [Number.NaN, '표본 없음'],
  [Number.POSITIVE_INFINITY, '표본 없음'],
  [2, '확인 불가'],
])('renders collection state %s as %s without inventing a successful sample', async (up, label) => {
  vi.spyOn(api, 'operations').mockResolvedValue({
    available: true, source: 'Prometheus', fetchedAt: '2026-10-14T00:00:00Z',
    values: up === undefined ? {} : { up }, series: {}, message: '',
  })
  const wrapper = mount(OperationsDashboard)
  wrappers.push(wrapper)
  await flushPromises()
  expect(wrapper.findAll('.operation-cards dd')[0]!.text()).toBe(label)
})
it('shows source outages explicitly without presenting previous metrics as current', async () => {
  vi.spyOn(api,'operations').mockResolvedValue({available:false,source:'Prometheus',fetchedAt:'2026-10-14T00:00:00Z',values:{},series:{},message:'Prometheus 연결 실패'})
  const wrapper=mount(OperationsDashboard); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.findAll('[role="alert"]').some(node => node.text().includes('Prometheus 연결 실패'))).toBe(true); expect(wrapper.find('.operation-cards').exists()).toBe(false)
})
