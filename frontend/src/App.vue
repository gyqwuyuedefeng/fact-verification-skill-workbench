<script setup lang="ts">
import { NConfigProvider, NDialogProvider, NMessageProvider, darkTheme, zhCN } from 'naive-ui'
</script>

<template>
  <NConfigProvider :theme="darkTheme" :locale="zhCN">
    <NDialogProvider>
      <NMessageProvider>
        <div class="app-shell">
          <aside class="side-rail">
            <div class="brand-mark">FV</div>
            <div class="brand-copy">
              <strong>事实核验</strong>
              <span>Skill Workbench</span>
            </div>
            <nav class="main-nav" aria-label="主导航">
              <div class="nav-group">
                <span>普通使用</span>
                <RouterLink to="/verification">事实核验对话</RouterLink>
              </div>
              <div class="nav-group">
                <span>管理控制台</span>
                <RouterLink to="/admin/evaluations">对照评测</RouterLink>
                <RouterLink to="/admin/skills">Skill 版本</RouterLink>
                <RouterLink to="/admin/releases">影子与发布</RouterLink>
              </div>
            </nav>
            <div class="rail-status"><i></i> 管理员演示账号 · 未启用权限隔离</div>
          </aside>
          <main class="page-frame">
            <RouterView />
          </main>
        </div>
      </NMessageProvider>
    </NDialogProvider>
  </NConfigProvider>
</template>
