import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'

import App from '../App.vue'

describe('App', () => {
  it('声明真实存在的站点图标，避免每个浏览器会话产生 404 控制台错误', () => {
    const html = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')
    expect(html).toContain('rel="icon"')
    expect(html).toContain('href="/favicon.svg"')
    expect(existsSync(resolve(process.cwd(), 'public/favicon.svg'))).toBe(true)
    // 下载报告后浏览器会直接展示 API 文本页，该页面不会读取 index.html，而会约定请求 /favicon.ico。
    expect(existsSync(resolve(process.cwd(), 'public/favicon.ico'))).toBe(true)
  })

  it('把普通对话入口与演示数据和三个管理员工作台入口清楚分组', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div>主页</div>' } },
        { path: '/verification', component: { template: '<div>核验</div>' } },
        { path: '/admin/evaluations', component: { template: '<div>评测</div>' } },
        { path: '/admin/skills', component: { template: '<div>版本</div>' } },
        { path: '/admin/releases', component: { template: '<div>发布</div>' } },
        { path: '/admin/demo-state', component: { template: '<div>演示数据</div>' } },
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
      '演示数据',
      '对照评测',
      'Skill 版本',
      '影子与发布',
    ])
    expect(wrapper.text()).not.toContain('多租户')
  })
})
