import { createRouter, createWebHistory } from 'vue-router'

import EvaluationPage from '../pages/EvaluationPage.vue'
import DemoStatePage from '../pages/DemoStatePage.vue'
import ReleasePage from '../pages/ReleasePage.vue'
import SkillLabPage from '../pages/SkillLabPage.vue'
import VerificationPage from '../pages/VerificationPage.vue'

export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/verification' },
    { path: '/verification', name: 'verification', component: VerificationPage },
    { path: '/admin/evaluations', name: 'evaluation', component: EvaluationPage },
    { path: '/admin/demo-state', name: 'demo-state', component: DemoStatePage },
    { path: '/admin/skills', name: 'skills', component: SkillLabPage },
    { path: '/admin/releases', name: 'releases', component: ReleasePage },
    { path: '/evaluation', redirect: '/admin/evaluations' },
    { path: '/skills', redirect: '/admin/skills' },
  ],
})
