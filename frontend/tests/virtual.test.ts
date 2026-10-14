import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { virtualRange } from '../src/virtual'
import VirtualList from '../src/components/VirtualList.vue'
describe('fixed row virtualization', () => {
  it('uses observer dimensions instead of forcing a synchronous mount layout', async () => {
    let resized!: (entries: { contentRect: { height: number } }[]) => void
    const disconnect = vi.fn()
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: typeof resized) { resized = callback }
      observe() { }
      disconnect = disconnect
    })
    const measured = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get')
    const wrapper = mount(VirtualList, { props: { items: Array.from({ length: 500 }, (_, index) => index), keys: Array.from({ length: 500 }, (_, index) => String(index)), label: 'test', rowHeight: 60 } })
    expect(measured).not.toHaveBeenCalled()
    resized([{ contentRect: { height: 180 } }]); await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.virtual-row').length).toBeLessThanOrEqual(8)
    wrapper.unmount(); expect(disconnect).toHaveBeenCalledTimes(1)
  })
  it('bounds a 100k logical range to viewport plus overscan', () => {
    const range = virtualRange(100_000, 600_000, 520, 60)
    expect(range.start).toBe(9995)
    expect(range.end - range.start).toBeLessThanOrEqual(20)
    expect(range.total).toBe(6_000_000)
  })
  it('skips redundant initial scroll writes but resets a scrolled or pending viewport', async () => {
    const wrapper = mount(VirtualList, { props: { items: Array.from({ length: 500 }, (_, index) => index), keys: Array.from({ length: 500 }, (_, index) => String(index)), label: 'test', rowHeight: 60 } })
    const viewport = wrapper.find('.virtual-viewport').element as HTMLElement
    let position = 0
    const writePosition = vi.fn((value: number) => { position = value })
    Object.defineProperty(viewport, 'scrollTop', { configurable: true, get: () => position, set: writePosition })
    wrapper.vm.reset(); expect(writePosition).not.toHaveBeenCalled()
    wrapper.vm.scrollToIndex(10); wrapper.vm.reset()
    expect(writePosition.mock.calls.map(call => call[0])).toEqual([600, 0])
    vi.stubGlobal('requestAnimationFrame', vi.fn().mockReturnValue(7))
    const cancelFrame = vi.fn(); vi.stubGlobal('cancelAnimationFrame', cancelFrame)
    position = 300; await wrapper.find('.virtual-viewport').trigger('scroll')
    wrapper.vm.reset()
    expect(position).toBe(0); expect(cancelFrame).toHaveBeenCalledWith(7)
    wrapper.unmount()
  })
  it('handles empty/start/end ranges', () => {
    expect(virtualRange(0, 0, 520, 60).end).toBe(0)
    expect(virtualRange(5, 0, 520, 60).end).toBe(5)
    expect(virtualRange(100, 5900, 520, 60).end).toBe(100)
  })
  it('mounts only a small number of rows and can reveal a distant row', async () => {
    const wrapper = mount(VirtualList, { props: { items: Array.from({ length: 100_000 }, (_, i) => i), keys: Array.from({ length: 100_000 }, (_, i) => String(i)), label: 'test', rowHeight: 60 }, slots: { default: ({ item }: { item: number }) => String(item) } })
    expect(wrapper.findAll('.virtual-row').length).toBeLessThanOrEqual(20)
    wrapper.vm.scrollToIndex(50_000)
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('50000')
    expect(wrapper.findAll('.virtual-row').length).toBeLessThanOrEqual(20)
    wrapper.unmount()
  })
  it('adjusts scroll position when an old cached page is evicted', async () => {
    const wrapper = mount(VirtualList, { props: { items: Array.from({ length: 500 }, (_, i) => i), keys: Array.from({ length: 500 }, (_, i) => String(i)), label: 'test', rowHeight: 60 } })
    wrapper.vm.scrollToIndex(200); wrapper.vm.discardRows(100)
    await wrapper.vm.$nextTick()
    expect((wrapper.find('.virtual-viewport').element as HTMLElement).scrollTop).toBe(6000)
    wrapper.unmount()
  })
})
