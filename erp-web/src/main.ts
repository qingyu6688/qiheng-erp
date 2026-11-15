import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElAlert,
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElSwitch,
  ElInputNumber,
  ElCheckbox,
  ElCheckboxGroup,
  ElPopconfirm,
} from 'element-plus'
import App from './App.vue'
import { router } from './router'
import { useSessionStore } from './stores/session'
import './styles/theme.scss'
import './styles/app.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
for (const component of [
  ElAlert,
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElSwitch,
  ElInputNumber,
  ElCheckbox,
  ElCheckboxGroup,
  ElPopconfirm,
]) {
  app.use(component)
}
app.use(router)
window.addEventListener('erp:session-expired', () => {
  useSessionStore(pinia).clear()
  void router.replace({ name: 'expired' })
})
app.mount('#app')
