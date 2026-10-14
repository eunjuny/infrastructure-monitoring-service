import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ResourceTree from '../src/components/ResourceTree.vue'
import { api } from '../src/api'
import { resource, page } from './fixtures'
let wrapper: VueWrapper | undefined
afterEach(() => wrapper?.unmount())
describe('hierarchy paging and path navigation', () => {
  it('replaces child pages and can return to the previous cursor', async () => {
    vi.spyOn(api, 'children').mockImplementation(async (_, cursor) => page([resource(cursor ? 2 : 1, 'CLUSTER')], cursor ? null : 'second'))
    wrapper = mount(ResourceTree, { props: { selected: '' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '다음 하위 항목')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('cluster-2'); expect(wrapper.text()).not.toContain('cluster-1')
    await wrapper.findAll('button').find(b => b.text() === '이전')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('cluster-1')
  })
  it('pins a search target outside the first child page without downloading all siblings', async () => {
    const cluster = resource(1, 'CLUSTER'), node = resource(100, 'NODE')
    const children = vi.spyOn(api, 'children').mockImplementation(async parent => page(parent ? [resource(2, 'NODE')] : [cluster]))
    const detail = vi.spyOn(api, 'detail').mockResolvedValue({ resource: node, path: [cluster, node], attributes: {} })
    wrapper = mount(ResourceTree, { props: { selected: 'NODE:100' } }); await flushPromises()
    await wrapper.vm.reveal([cluster, node]); await flushPromises()
    expect(wrapper.text()).toContain('node-100')
    expect(wrapper.find('.tree-item.selected').text()).toContain('node-100')
    expect(detail).toHaveBeenCalledTimes(1); expect(children).toHaveBeenCalledTimes(3)
  })
  it('caps expanded branches at eight and refetches evicted collapsed caches', async () => {
    const roots = Array.from({ length: 30 }, (_, i) => resource(i + 1, 'CLUSTER'))
    const children = vi.spyOn(api, 'children').mockImplementation(async parent => page(parent ? [] : roots))
    wrapper = mount(ResourceTree, { props: { selected: '' } }); await flushPromises()
    // Return to the start of the virtual window as each branch gets opened.
    for (const cluster of roots) {
      const component = wrapper.findComponent({ name: 'VirtualList' })
      component.vm.scrollToIndex(roots.indexOf(cluster))
      await wrapper.vm.$nextTick()
      await wrapper.find(`button[aria-label="${cluster.name} 펼치기"]`).trigger('click'); await flushPromises()
    }
    const virtual = wrapper.findComponent({ name: 'VirtualList' }); virtual.vm.scrollToIndex(0); await wrapper.vm.$nextTick()
    expect(wrapper.find('button[aria-label="cluster-1 펼치기"]').attributes('aria-expanded')).toBe('false')
    const calls = children.mock.calls.length
    await wrapper.find('button[aria-label="cluster-1 펼치기"]').trigger('click'); await flushPromises()
    expect(children).toHaveBeenCalledTimes(calls + 1)
  })
  it('preserves a pinned path target during refresh and offers a root reset', async () => {
    const cluster = resource(1, 'CLUSTER'), node = resource(100, 'NODE')
    vi.spyOn(api, 'children').mockImplementation(async parent => page(parent ? [resource(2, 'NODE')] : [cluster]))
    const detail = vi.spyOn(api, 'detail').mockResolvedValue({ resource: node, path: [cluster, node], attributes: {} })
    wrapper = mount(ResourceTree, { props: { selected: 'NODE:100' } }); await flushPromises()
    await wrapper.vm.reveal([cluster, node]); await wrapper.vm.refresh(); await flushPromises()
    expect(wrapper.text()).toContain('node-100'); expect(detail).toHaveBeenCalledTimes(2)
    await wrapper.findAll('button').find(b => b.text() === '처음')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('node-100'); expect(wrapper.text()).toContain('cluster-1')
  })
})
