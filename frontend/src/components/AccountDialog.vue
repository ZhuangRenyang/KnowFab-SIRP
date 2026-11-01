<template>
  <el-dialog v-model="visible" :title="t('acctDlg.title')" width="420px" :append-to-body="true">
    <div class="who">
      <div><span class="lbl">{{ t('acctDlg.curUser') }}</span><b>{{ auth.displayName }}</b></div>
      <div><span class="lbl">{{ t('acctDlg.role') }}</span>{{ auth.roleLabel }}</div>
      <div><span class="lbl">{{ t('acctDlg.username') }}</span><span class="num">{{ auth.user?.username || '--' }}</span></div>
    </div>
    <el-divider />
    <el-form label-width="110px" @submit.prevent>
      <el-form-item :label="t('acctDlg.oldPw')">
        <el-input v-model="oldP" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item :label="t('acctDlg.newPw')">
        <el-input v-model="newP" type="password" show-password autocomplete="new-password" :placeholder="t('acctDlg.newPh')" />
      </el-form-item>
      <el-form-item :label="t('acctDlg.newPw2')">
        <el-input v-model="newP2" type="password" show-password autocomplete="new-password" />
      </el-form-item>
    </el-form>
    <div v-if="msg" class="msg" :class="{ bad: !okFlag }">{{ msg }}</div>
    <template #footer>
      <el-button @click="visible = false">{{ t('acctDlg.close') }}</el-button>
      <el-button type="primary" :loading="busy" @click="submit">{{ t('acctDlg.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()

const visible = defineModel<boolean>({ default: false })
const auth = useAuthStore()

const oldP = ref('')
const newP = ref('')
const newP2 = ref('')
const msg = ref('')
const okFlag = ref(false)
const busy = ref(false)

watch(visible, (v) => { if (v) { oldP.value = ''; newP.value = ''; newP2.value = ''; msg.value = '' } })

const canSubmit = computed(() => oldP.value && newP.value.length >= 8 && newP.value === newP2.value)

async function submit() {
  if (busy.value) return
  if (!oldP.value) { msg.value = t('acctDlg.needOld'); okFlag.value = false; return }
  if (newP.value.length < 8) { msg.value = t('acctDlg.short'); okFlag.value = false; return }
  if (newP.value !== newP2.value) { msg.value = t('acctDlg.mismatch'); okFlag.value = false; return }
  busy.value = true
  try {
    const r = await auth.changePassword(oldP.value, newP.value)
    okFlag.value = !!r.ok
    msg.value = r.message || (r.ok ? t('acctDlg.done') : t('acctDlg.fail'))
    if (r.ok) ElMessage.success(t('acctDlg.doneToast'))
    if (r.ok) { oldP.value = ''; newP.value = ''; newP2.value = '' }
  } catch (e: any) {
    okFlag.value = false
    msg.value = e?.message || t('acctDlg.fail')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.who div { display: flex; gap: 10px; margin: 4px 0; font-size: var(--text-sm); }
.who .lbl { width: 60px; color: var(--muted); }
.msg { margin-top: 4px; font-size: var(--text-sm); color: var(--run); }
.msg.bad { color: var(--alarm); }
</style>
