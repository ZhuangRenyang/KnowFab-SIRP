<template>
  <div class="panel">
    <div class="p-head"><h3>数据库密码</h3>
      <span class="hint">修改 devicehub 账号的 MySQL 密码，改完需重启系统</span>
    </div>

    <div class="tip">
      <b>默认账号：</b><code>devicehub</code>
      <b>默认密码：</b><code>DeviceHub2026</code>
      <span>（装好后没改过，就在「当前密码」里填这个；改过则填你记录的新密码）</span>
    </div>

    <el-form label-width="110px" class="dbform" @submit.prevent>
      <el-form-item label="当前密码">
        <el-input v-model="oldPwd" type="password" show-password
                  placeholder="数据库当前密码" autocomplete="off" style="max-width:320px" />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input v-model="newPwd" type="password" show-password
                  placeholder="6~64 位，字母/数字/!@#$%^&*._+-" autocomplete="new-password" style="max-width:320px" />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input v-model="confirmPwd" type="password" show-password
                  placeholder="再输一遍" autocomplete="new-password" style="max-width:320px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="submit">修改密码</el-button>
        <span class="msg">{{ msg }}</span>
      </el-form-item>
    </el-form>

    <div class="desc">
      修改成功后新密码会写入 <code>config/application.yml</code>（重启生效，优先级：环境变量 &gt; 此文件 &gt; 内置默认值）。
      只修改后端实际连接的数据库账号；若存在同账号其他 host 条目（如 localhost 与 127.0.0.1 两条），
      其余条目需用 DBA 工具手动同步。
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { systemApi } from '@/api/system'

const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const saving = ref(false)
const msg = ref('')

async function submit() {
  if (!oldPwd.value) { msg.value = '请填写当前密码'; return }
  if (!newPwd.value) { msg.value = '请填写新密码'; return }
  if (newPwd.value !== confirmPwd.value) { msg.value = '两次输入的新密码不一致'; return }
  saving.value = true
  try {
    const r = await systemApi.changeDbPassword(oldPwd.value, newPwd.value)
    if (!r.ok) {
      msg.value = r.message || '修改失败'
      return
    }
    msg.value = ''
    oldPwd.value = newPwd.value = confirmPwd.value = ''
    await ElMessageBox.alert(r.message, '修改成功', { confirmButtonText: '知道了', type: 'success' })
  } catch (e: any) {
    msg.value = ''
    ElMessage.error(e?.message || '修改失败')
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
