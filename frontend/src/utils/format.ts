/* 数字/金额/时间显示（库存与单据共用）
   数量保留最多 4 位（库位最小单位可能是 kg/米），去掉无意义的尾零；
   金额固定 2 位并加千分位。 */

export function qty(v?: number | null, digits = 4): string {
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '0'
  const s = n.toFixed(digits)
  const trimmed = s.replace(/\.?0+$/, '')
  const [i, d] = trimmed.split('.')
  const sign = i.startsWith('-') ? '-' : ''
  const int = i.replace('-', '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return sign + int + (d ? '.' + d : '')
}

export function money(v?: number | null, digits = 2): string {
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '0.00'
  return n.toFixed(digits).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export function dt(ts?: number | null): string {
  if (!ts) return '--'
  const d = new Date(ts), p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function dayText(ts?: number | null): string {
  if (!ts) return '--'
  const d = new Date(ts), p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
