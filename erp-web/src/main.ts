import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElAlert } from 'element-plus/es/components/alert/index.mjs'
import { ElButton } from 'element-plus/es/components/button/index.mjs'
import { ElConfigProvider } from 'element-plus/es/components/config-provider/index.mjs'
import { ElDialog } from 'element-plus/es/components/dialog/index.mjs'
import { ElDrawer } from 'element-plus/es/components/drawer/index.mjs'
import { ElCascader } from 'element-plus/es/components/cascader/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import { ElInput } from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTag } from 'element-plus/es/components/tag/index.mjs'
import { ElSwitch } from 'element-plus/es/components/switch/index.mjs'
import { ElInputNumber } from 'element-plus/es/components/input-number/index.mjs'
import { ElCheckbox, ElCheckboxGroup } from 'element-plus/es/components/checkbox/index.mjs'
import { ElPopconfirm } from 'element-plus/es/components/popconfirm/index.mjs'
import { ElRadio, ElRadioButton, ElRadioGroup } from 'element-plus/es/components/radio/index.mjs'
import App from './App.vue'
import { router } from './router'
import { useSessionStore } from './stores/session'
import './styles/theme.scss'
import './styles/app.css'
import './styles/art-business.css'
import './styles/workspace.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
for (const component of [
  ElAlert,
  ElButton,
  ElConfigProvider,
  ElDialog,
  ElDrawer,
  ElCascader,
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
  ElRadio,
  ElRadioButton,
  ElRadioGroup,
]) {
  app.use(component)
}
app.use(router)
window.addEventListener('erp:session-expired', () => {
  useSessionStore(pinia).clear()
  void router.replace({ name: 'expired' })
})
window.addEventListener('erp:session-kicked', () => {
  useSessionStore(pinia).clear()
  void router.replace({ name: 'login' })
})
app.mount('#app')
