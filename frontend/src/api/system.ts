import { http } from './http'

/* 系统域 API：设置（配置驱动渲染）、升级、结构迁移、演示数据清除。 */

export interface SettingItem {
  key: string
  label: string
  type: 'BOOL' | 'INT' | 'AREA' | 'TEXT'
  value: string
  defaultValue: string
  overridden: boolean
  scope: string
  desc: string
  min: number
  max: number
  pendingRestart: boolean
  fromYml: boolean
}
export interface SettingGroup { name: string; items: SettingItem[] }
export interface ReadonlyItem { label: string; value: string; why: string }
export interface SettingsView {
  ok: boolean
  groups: SettingGroup[]
  pendingRestart: number
  readonly: ReadonlyItem[]
}

export interface UpdateStatus {
  ok: boolean
  version: string
  buildTime: string
  applied: { version?: string; at?: string }
  schemaVersion: string
  allowWebRestart: boolean
  updateDir: string
  pending: {
    present: boolean
    toVersion?: string
    fromVersion?: string
    notes?: string
    hasMigration?: boolean
    blocked?: string
  }
  backups: { dir: string }[]
  rollbackRequested: boolean
}

export interface CheckSource { source: string; ok: boolean; latest?: string; error?: string }
export interface CheckResult {
  ok: boolean
  hasUpdate?: boolean
  latest?: string
  notes?: string
  url?: string
  currentVersion?: string
  message?: string
  sources?: CheckSource[]
}

export interface MigrationFile {
  version: string
  file: string
  size: number
  applied: boolean
  statements?: number
  blocked?: string | null
  warnings?: string[]
}
export interface AppliedMigration {
  version: string
  ok: boolean
  at: string
  statements: number
  attempts: number
  error?: string
}
export interface MigrationsView {
  ok: boolean
  dir: string
  schemaVersion: string
  applied: AppliedMigration[]
  files: MigrationFile[]
  waiting: number
  failedVersion: string | null
  failedError: string | null
  lastBootError: string | null
  lastBootCount: number
}

export type PurgeKind = 'energy' | 'report' | 'demand'
const PURGE_URL: Record<PurgeKind, string> = {
  energy: '/api/energy/purge',
  report: '/api/reports/purge',
  demand: '/api/energy/demand/purge'
}

/* 数据库密码页面化修改（管理员） */
export interface DbPasswordResult {
  ok: boolean
  message: string
  restartRequired?: boolean
}

export const systemApi = {
  version: () => http.get<{ ok: boolean; version: string; buildTime: string; schemaVersion: string }>('/api/system/version'),
  status: () => http.get<UpdateStatus>('/api/system/update/status'),
  check: () => http.get<CheckResult>('/api/system/update/check'),
  download: (url: string) => http.post<{ ok: boolean; message?: string; version?: string }>('/api/system/update/download', { url }),
  upload: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<{ ok: boolean; message?: string; version?: string; currentVersion?: string; fileCount?: number; notes?: string }>(
      '/api/system/update/upload', fd)
  },
  apply: () => http.post<{ ok: boolean; message?: string; restarting?: boolean }>('/api/system/update/apply', {}),
  rollback: () => http.post<{ ok: boolean; message?: string }>('/api/system/update/rollback', {}),
  cancel: () => http.post<{ ok: boolean; message?: string }>('/api/system/update/cancel', {}),

  settings: () => http.get<SettingsView>('/api/system/settings'),
  saveSettings: (values: Record<string, string>) =>
    http.post<{ ok: boolean; message?: string; warnings?: string[]; errors?: string[] }>('/api/system/settings', values),
  resetSetting: (key: string) =>
    http.del<{ ok: boolean; message?: string }>(`/api/system/settings/${encodeURIComponent(key)}`),

  migrations: () => http.get<MigrationsView>('/api/system/migrations'),
  migrationSql: (version: string) =>
    http.get<{ ok: boolean; version: string; statements?: string[]; sql?: string[] }>(
      `/api/system/migrations/${encodeURIComponent(version)}/sql`),

  purge: (kind: PurgeKind) =>
    http.post<{ ok: boolean; message?: string }>(PURGE_URL[kind], { confirm: true }),

  changeDbPassword: (oldPassword: string, newPassword: string) =>
    http.post<DbPasswordResult>('/api/system/settings/db-password', { oldPassword, newPassword })
}

export const PURGE_META: { kind: PurgeKind; name: string; switchKey: string }[] = [
  { kind: 'energy', name: '能耗（电费、峰谷电量）', switchKey: 'energy.demo-seed' },
  { kind: 'report', name: '报表（班次产量、稼动率）', switchKey: 'report.demo-seed' },
  { kind: 'demand', name: '需量（15 分钟窗口、基本电费）', switchKey: 'demand.demo-seed' }
]
