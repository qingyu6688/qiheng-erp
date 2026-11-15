<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { OfficeBuilding, House, Lock, SwitchButton } from '@element-plus/icons-vue'
import { useSessionStore } from '../stores/session'
import { messageOf } from '../api/http'
import BrandMark from './BrandMark.vue'
import { resourceGroups, resourceSpecs } from '../pages/resources'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const loggingOut = ref(false)
const error = ref('')
const title = computed(() =>
  String(route.meta.title ?? (route.name === 'password' ? '账户设置' : '仓库管理')),
)
const groups = computed(() =>
  session.user?.mustChangePassword
    ? []
    : resourceGroups
        .map((group) => ({
          title: group.title,
          resources: group.paths
            .map((path) => resourceSpecs[path]!)
            .filter((spec) => session.can(spec.permission + ':view')),
        }))
        .filter(
          (group) =>
            group.resources.length > 0 ||
            (group.title === '基础资料' && session.can('warehouse:view')),
        ),
)

async function logout(): Promise<void> {
  loggingOut.value = true
  error.value = ''
  try {
    await session.signOut()
    await router.replace('/login')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    loggingOut.value = false
  }
}
</script>
<template>
  <div class="shell">
    <aside class="sidebar">
      <BrandMark />
      <nav aria-label="主导航">
        <div class="nav-group">
          <template v-for="group in groups" :key="group.title">
            <div class="nav-caption">{{ group.title }}</div>
            <router-link
              v-if="group.title === '基础资料' && session.can('warehouse:view')"
              class="nav-link"
              to="/warehouses"
              ><House />仓库管理</router-link
            >
            <router-link
              v-for="spec in group.resources"
              :key="spec.path"
              class="nav-link"
              :to="'/' + spec.path"
              >{{ spec.title }}</router-link
            >
          </template>
        </div>
        <div class="nav-group">
          <div class="nav-caption">账户</div>
          <router-link class="nav-link" to="/account/password"><Lock />修改密码</router-link>
        </div>
      </nav>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span class="breadcrumb">{{ title }}</span>
        <div class="topbar-actions">
          <OfficeBuilding class="icon company-icon" aria-hidden="true" />
          <label for="company-select" class="sr-only">当前公司</label>
          <el-select
            id="company-select"
            class="company-select"
            :model-value="session.companyId"
            placeholder="请选择公司"
            @change="session.selectCompany"
          >
            <el-option
              v-for="c in session.user?.companies"
              :key="c.id"
              :value="c.id"
              :label="c.name"
            />
          </el-select>
          <span class="account-name">{{
            session.user?.displayName || session.user?.username
          }}</span>
          <el-button
            text
            :loading="loggingOut"
            aria-label="退出登录"
            title="退出登录"
            @click="logout"
          >
            <SwitchButton class="icon" />
          </el-button>
        </div>
      </header>
      <main class="content">
        <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
        <router-view :key="route.path + ':' + session.companyId" />
      </main>
    </div>
  </div>
</template>
