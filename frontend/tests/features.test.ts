import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ScopeFilters from '../src/components/ScopeFilters.vue'
import ResourceEvents from '../src/components/ResourceEvents.vue'
import OperationsDashboard from '../src/components/OperationsDashboard.vue'
import { api } from '../src/api'
import { page, resource } from './fixtures'
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
beforeEach(() => { vi.spyOn(api, 'alerts').mockResolvedValue({ available: false, source: 'Prometheus', fetchedAt: '', rules: [], message: '경보 미설정' }) })
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
it('uses Prometheus data and does not substitute missing values with zero', async () => {
  vi.spyOn(api,'operations').mockResolvedValue({available:true,source:'Prometheus',fetchedAt:'2026-10-14T00:00:00Z',values:{up:1,heap:1048576,apiP95:null},series:{},message:''})
  const wrapper=mount(OperationsDashboard); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.text()).toContain('Prometheus'); expect(wrapper.text()).toContain('표본 없음'); expect(wrapper.findAll('.operation-cards > div')).toHaveLength(10)
})
it('shows source outages explicitly without presenting previous metrics as current', async () => {
  vi.spyOn(api,'operations').mockResolvedValue({available:false,source:'Prometheus',fetchedAt:'2026-10-14T00:00:00Z',values:{},series:{},message:'Prometheus 연결 실패'})
  const wrapper=mount(OperationsDashboard); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.findAll('[role="alert"]').some(node => node.text().includes('Prometheus 연결 실패'))).toBe(true); expect(wrapper.find('.operation-cards').exists()).toBe(false)
})
