<template>
  <Transition appear>
    <section class="dead-letters" aria-labelledby="dead-letters-title">
      <header class="toolbar">
        <h1 id="dead-letters-title">统计死信</h1>
        <el-button :disabled="busy" @click="load()" :loading="loading">刷新</el-button>
      </header>
      <Transition mode="out-in">
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-table v-else :data="items" row-key="id" :empty-text="loading ? '正在查询' : '暂无统计死信'">
          <el-table-column prop="id" label="死信 ID" min-width="175" />
          <el-table-column label="失败时间" min-width="175">
            <template #default="{ row }">{{ new Date(row.failedAt).toLocaleString('zh-CN') }}</template>
          </el-table-column>
          <el-table-column prop="retryCount" label="重试次数" width="95" />
          <el-table-column prop="error" label="失败摘要" min-width="220" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button
                type="primary"
                :disabled="busy"
                :loading="replaying === row.id"
                @click="replay(row.id)"
              >重放</el-button>
            </template>
          </el-table-column>
        </el-table>
      </Transition>
      <nav v-if="!error" class="pagination" aria-label="统计死信分页">
        <el-button v-if="before" :disabled="busy" @click="load()">回到最新</el-button>
        <el-button v-if="nextBefore" :disabled="busy" @click="load(nextBefore)">更早记录</el-button>
      </nav>
    </section>
  </Transition>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import statsDeadLetter from '@/api/modules/statsDeadLetter'

const items = ref([])
const before = ref()
const nextBefore = ref()
const loading = ref(false)
const replaying = ref('')
const error = ref('')
const busy = computed(() => loading.value || Boolean(replaying.value))

async function load(cursor) {
  loading.value = true
  error.value = ''
  try {
    const { data } = await statsDeadLetter.list(cursor)
    if (data.code !== '0') {
      error.value = data.message
      return
    }
    items.value = data.data.items
    nextBefore.value = data.data.nextBefore
    before.value = cursor
  } catch (err) {
    error.value = err.response?.data?.message || '统计死信查询失败'
  } finally {
    loading.value = false
  }
}

async function replay(id) {
  replaying.value = id
  try {
    await ElMessageBox.confirm(
      '历史死信可能已写入部分统计。请先核对原统计，再将该消息重新入队。',
      '重放统计死信',
      { type: 'warning', confirmButtonText: '确认重放', cancelButtonText: '取消' }
    )
    const { data } = await statsDeadLetter.replay(id)
    if (data.code !== '0') {
      ElMessage.error(data.message)
      return
    }
    ElMessage.success('统计消息已重新入队')
    await load(before.value)
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    ElMessage.error(err.response?.data?.message || '重放请求失败，请刷新列表确认状态')
    if (err.response?.status === 404) await load(before.value)
  } finally {
    replaying.value = ''
  }
}

onMounted(() => load())
</script>

<style scoped>
.dead-letters {
  box-sizing: border-box;
  max-width: 1120px;
  height: 100%;
  margin: 0 auto;
  padding: 16px;
  overflow: auto;
}

.toolbar,
.pagination {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-block: 8px;
}

.toolbar {
  justify-content: space-between;
}

.toolbar h1 {
  margin: 0;
  font-size: 20px;
}

.el-button {
  min-height: 44px;
  transition: transform 120ms ease;
}

.el-button:active:not(:disabled) {
  transform: scale(0.97);
}

.v-enter-active,
.v-leave-active {
  transition: opacity 180ms ease, transform 180ms ease;
}

.v-enter-from,
.v-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

@media (prefers-reduced-motion: reduce) {
  .v-enter-active,
  .v-leave-active,
  .el-button {
    transition: none;
  }

  .el-button:active:not(:disabled) {
    transform: none;
  }
}
</style>
