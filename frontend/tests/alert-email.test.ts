import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AlertEmailSettings from '../src/components/AlertEmailSettings.vue'
import { api } from '../src/api'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
const snapshot = { available: true, recipient: '', enabled: false, smtpConfigured: false }

it('allows saving a recipient but not enabling email before SMTP setup', async () => {
  vi.spyOn(api, 'alertEmail').mockResolvedValue(snapshot)
  const save = vi.spyOn(api, 'saveAlertEmail').mockResolvedValue({ ...snapshot, recipient: 'admin@example.invalid' })
  const wrapper = mount(AlertEmailSettings); wrappers.push(wrapper); await flushPromises()
  expect(wrapper.text()).toContain('SMTP 미설정')
  expect(wrapper.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
  await wrapper.get('input[type="email"]').setValue('admin@example.invalid')
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(save).toHaveBeenCalledWith('admin@example.invalid', false, expect.any(AbortSignal))
  expect(wrapper.text()).toContain('적용됐습니다')
})

it('sends enabled state only on explicit submit and does not claim success on failure', async () => {
  vi.spyOn(api, 'alertEmail').mockResolvedValue({ ...snapshot, smtpConfigured: true })
  const save = vi.spyOn(api, 'saveAlertEmail').mockRejectedValue(new Error('unavailable'))
  const wrapper = mount(AlertEmailSettings); wrappers.push(wrapper); await flushPromises()
  await wrapper.get('input[type="email"]').setValue('admin@example.invalid')
  await wrapper.get('input[type="checkbox"]').setValue(true)
  expect(save).not.toHaveBeenCalled()
  await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('저장하지 못했습니다')
  expect(wrapper.text()).not.toContain('적용됐습니다')
})

it('shows a bounded error and cancels on unmount', async () => {
  const read = vi.spyOn(api, 'alertEmail').mockRejectedValue(new Error('offline'))
  const wrapper = mount(AlertEmailSettings); await flushPromises()
  expect(wrapper.get('[role="alert"]').text()).toContain('확인할 수 없습니다')
  expect(wrapper.find('form').exists()).toBe(false)
  const signal = read.mock.calls[0]![0]!
  wrapper.unmount()
  expect(signal.aborted).toBe(true)
})
