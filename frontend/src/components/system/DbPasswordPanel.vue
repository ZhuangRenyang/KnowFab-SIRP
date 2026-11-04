<template>
  <div class="panel">
    <div class="p-head"><h3>{{ t('db.title') }}</h3>
      <span class="hint">{{ t('db.hint') }}</span>
    </div>

    <div class="tip">
      <b>{{ t('db.defaultAccount') }}</b><code>admin</code>
      <b>{{ t('db.defaultPassword') }}</b><code>123456</code>
      <span>{{ t('db.defaultNote') }}</span>
    </div>

    <el-form label-width="110px" class="dbform" @submit.prevent>
      <el-form-item :label="t('db.oldPwd')">
        <el-input v-model="oldPwd" type="password" show-password
                  :placeholder="t('db.phOld')" autocomplete="off" style="max-width:320px" />
      </el-form-item>
      <el-form-item :label="t('db.newPwd')">
        <el-input v-model="newPwd" type="password" show-password
                  :placeholder="t('db.phNew')" autocomplete="new-password" style="max-width:320px" />
      </el-form-item>
      <el-form-item :label="t('db.confirmPwd')">
        <el-input v-model="confirmPwd" type="password" show-password
                  :placeholder="t('db.phConfirm')" autocomplete="new-password" style="max-width:320px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit">{{ t('db.submit') }}</el-button>
        <span class="msg">{{ msg }}</span>
      </el-form-item>
    </el-form>

    <div class="desc">
      {{ t('db.footer') }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { systemApi } from '@/api/system'

const { t } = useI18n()

const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const saving = ref(false)
const msg = ref('')

async function submit() {
  if (!oldPwd.value) { msg.value = t('db.needOld'); return }
  if (!newPwd.value) { msg.value = t('db.needNew'); return }
  if (newPwd.value !== confirmPwd.value) { msg.value = t('db.mismatch'); return }
  saving.value = true
  try {
    const r = await systemApi.changeDbPassword(oldPwd.value, newPwd.value)
    if (!r.ok) {
      msg.value = r.message || t('db.failed')
      return
    }
    msg.value = ''
    oldPwd.value = newPwd.value = confirmPwd.value = ''
    await ElMessageBox.alert(r.message, t('db.successTitle'), { confirmButtonText: t('db.okBtn'), type: 'success' })
  } catch (e: any) {
    msg.value = ''
    ElMessage.error(e?.message || t('db.failed'))
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  box-shadow: var(--elev-raised); padding: var(--space-4); margin-bottom: var(--space-4); }
.p-head { display: flex; align-items: baseline; gap: var(--space-3); margin-bottom: var(--space-3); }
.p-head h3 { margin: 0; font-size: var(--text-md); font-weight: 600; }
.hint { font-size: var(--text-xs); color: var(--muted); }
.tip {
  margin: 4px 0 var(--space-3); padding: 8px 12px; border-radius: var(--radius-sm);
  background: var(--bg-soft, var(--surface-2)); color: var(--fg-2); font-size: var(--text-xs);
  display: flex; gap: 6px; flex-wrap: wrap; align-items: baseline;
}
.tip b { font-weight: 500; color: var(--muted); }
.tip code {
  font-family: var(--font-mono); font-size: var(--text-xs);
  background: var(--surface); padding: 1px 6px; border-radius: 4px; color: var(--fg);
}
.tip span { color: var(--muted); }
.dbform { max-width: 560px; }
.msg { margin-left: var(--space-3); font-size: var(--text-xs); color: var(--muted); }
.desc { font-size: var(--text-xs); color: var(--meta); line-height: 1.7; }
.desc code { background: var(--bg); padding: 1px 5px; border-radius: 4px; }
</style>
