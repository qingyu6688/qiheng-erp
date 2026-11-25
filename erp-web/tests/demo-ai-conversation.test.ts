import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DemoAiConversation from '../src/demo/ai/DemoAiConversation.vue'
import DemoAiAnswersPage from '../src/demo/ai/DemoAiAnswersPage.vue'
import { demoAiAnswers } from '../src/demo/ai-data'
import { aiRead } from '../src/api/ai'

vi.mock('../src/api/ai', () => ({ aiRead: vi.fn() }))
const answer = demoAiAnswers[0]!
const stubs = {
  AiMarkdown: { props: ['content'], template: '<div class="answer-text">{{ content }}</div>' },
  AiSources: { props: ['sources'], template: '<div class="test-sources">引用来源</div>' },
  AiToolResults: { props: ['runs'], template: '<div>查询记录</div>' },
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: false }))
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('AI 预设对话展示', () => {
  it('默认显示完整的用户和助手消息，引用与查询记录可以访问', async () => {
    const wrapper = mount(DemoAiConversation, { props: { answer }, global: { stubs } })
    expect(wrapper.get('article[aria-label="用户提问"]').text()).toContain(answer.question)
    expect(wrapper.get('article[aria-label="助手回答"]').text()).toContain(answer.content)
    expect(wrapper.getComponent(stubs.AiSources).props('sources')).toHaveLength(answer.sourceIds.length)
    expect(wrapper.getComponent(stubs.AiToolResults).props('runs')).toEqual(answer.tools)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('只读预设对话')
    wrapper.unmount()
  })

  it('播放逐步呈现已有回答，可以提前完成且不发起 AI 请求', async () => {
    const wrapper = mount(DemoAiConversation, { props: { answer }, global: { stubs } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('[role="status"]').text()).toContain('正在播放预设回答')
    expect(wrapper.get('.answer-text').text()).toBe('')
    await vi.advanceTimersByTimeAsync(110)
    expect(wrapper.get('.answer-text').text().length).toBeGreaterThan(0)
    expect(wrapper.get('.answer-text').text().length).toBeLessThan(answer.content.length)
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('.answer-text').text()).toBe(answer.content)
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
    expect(aiRead).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('自动播完后恢复引用，切换场景和卸载均清理计时器', async () => {
    const wrapper = mount(DemoAiConversation, { props: { answer }, global: { stubs } })
    await wrapper.get('button').trigger('click')
    await vi.advanceTimersByTimeAsync(answer.content.length * 55)
    expect(wrapper.get('.answer-text').text()).toBe(answer.content)
    expect(wrapper.find('.test-sources').exists()).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    await wrapper.get('button').trigger('click')
    await wrapper.setProps({ answer: demoAiAnswers[1]! })
    expect(wrapper.get('.answer-text').text()).toBe(demoAiAnswers[1]!.content)
    expect(vi.getTimerCount()).toBe(0)
    await wrapper.get('button').trigger('click')
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('减少动态效果时直接展示完整对话', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }))
    const wrapper = mount(DemoAiConversation, { props: { answer }, global: { stubs } })
    await wrapper.get('button').trigger('click')
    expect(wrapper.get('.answer-text').text()).toBe(answer.content)
    expect(vi.getTimerCount()).toBe(0)
    wrapper.unmount()
  })

  it('选择场景直接更换会话，筛选结果保留仍可见的当前场景', async () => {
    vi.mocked(aiRead).mockResolvedValueOnce(demoAiAnswers).mockResolvedValueOnce(demoAiAnswers.slice(0, 2)).mockResolvedValueOnce([])
    const wrapper = mount(DemoAiAnswersPage, { global: { stubs: {
      DemoAiConversation: { props: ['answer'], template: '<div class="selected-conversation">{{ answer.question }}</div>' },
      ElInput: { template: '<input />' }, ElSelect: true, ElOption: true, ElAlert: true,
      ElButton: { template: '<button><slot /></button>' },
    } } })
    await flushPromises()
    expect(wrapper.get('.selected-conversation').text()).toBe(answer.question)
    await wrapper.findAll('.scenario-button')[1]!.trigger('click')
    expect(wrapper.get('.selected-conversation').text()).toBe(demoAiAnswers[1]!.question)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.selected-conversation').text()).toBe(demoAiAnswers[1]!.question)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.selected-conversation').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有找到相关回答样例')
    wrapper.unmount()
  })
})
