import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import App from '../App.vue'

describe('App', () => {
  it('把普通对话入口与三个管理员工作台入口清楚分组', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div>主页</div>' } },
        { path: '/verification', component: { template: '<div>核验</div>' } },
        { path: '/admin/evaluations', component: { template: '<div>评测</div>' } },
        { path: '/admin/skills', component: { template: '<div>版本</div>' } },
        { path: '/admin/releases', component: { template: '<div>发布</div>' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, {
      global: { plugins: [createPinia(), router] },
    })

    expect(wrapper.text()).toContain('普通使用')
    expect(wrapper.text()).toContain('管理控制台')
    expect(wrapper.findAll('.main-nav a').map((link) => link.text())).toEqual([
      '事实核验对话',
      '对照评测',
      'Skill 版本',
      '影子与发布',
    ])
    expect(wrapper.text()).not.toContain('多租户')
  })
})
