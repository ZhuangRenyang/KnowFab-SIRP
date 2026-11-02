#!/bin/bash
# DH-24 操作审计 —— 端到端验证
# 用法：bash tools/verify-dh24.sh <base_url>
BASE="${1:-http://127.0.0.1:8091}"
CK=/tmp/ck24-admin.txt
CK2=/tmp/ck24-leader.txt
FAIL=0

say() { printf "\n=== %s ===\n" "$1"; }
ok()  { printf "  [OK]   %s\n" "$1"; }
bad() { printf "  [FAIL] %s\n" "$1"; FAIL=1; }

say "登录"
curl -s --max-time 8 -c $CK -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}' -o /dev/null -w "admin login: %{http_code}\n"

say "触发各分类写操作"
# SYSTEM：系统设置
curl -s --max-time 8 -b $CK -X POST "$BASE/api/system/settings" -H "Content-Type: application/json" \
  -d '{"audit.test.marker":"dh24"}' -o /dev/null -w "settings: %{http_code}\n"
# REPORT：班次设置
curl -s --max-time 8 -b $CK -X POST "$BASE/api/reports/shifts" -H "Content-Type: application/json" \
  -d '{"name":"测试班次","segments":[{"name":"白班","start":"08:00","end":"20:00"}]}' -o /dev/null -w "shifts: %{http_code}\n"
# ALARM：告警规则
curl -s --max-time 8 -b $CK -X POST "$BASE/api/alarms/rules" -H "Content-Type: application/json" \
  -d '{"name":"dh24测试规则","metric":"kwh","op":"GT","threshold":99999,"durationSec":60,"enabled":true}' \
  -o /dev/null -w "alarm rule: %{http_code}\n"
# USER：用户
curl -s --max-time 8 -b $CK -X POST "$BASE/api/users" -H "Content-Type: application/json" \
  -d '{"username":"dh24_probe","password":"Dh24Probe2026","role":"VIEWER","displayName":"DH24探针"}' \
  -o /dev/null -w "user create: %{http_code}\n"
# MANUAL：补录
curl -s --max-time 8 -b $CK -X POST "$BASE/api/manual" -H "Content-Type: application/json" \
  -d '{"deviceCode":"PRESS-01","fromDay":"2026-10-15","toDay":"2026-10-15","kwh":12.5,"output":30,"reason":"dh24审计验证"}' \
  -o /dev/null -w "manual: %{http_code}\n"
# ENERGY：电价方案
curl -s --max-time 8 -b $CK "$BASE/api/energy/tariff" -o /dev/null -w "tariff read: %{http_code}\n"
# 登录失败 → SYSTEM/LOGIN fail
curl -s --max-time 8 -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"dh24_probe","password":"wrong-password"}' -o /dev/null -w "bad login: %{http_code}\n"

say "查询审计（全量）"
curl -s --max-time 15 -b $CK "$BASE/api/audit?from=2026-10-01&to=2026-11-30&limit=500" -o /tmp/audit24.json
python - <<'PY'
import json, io, collections
d = json.load(io.open('/tmp/audit24.json', encoding='utf-8'))
rows = d.get('rows', [])
print('  总条数:', len(rows))
c = collections.Counter((r['category'], r['action']) for r in rows)
for (cat, act), n in sorted(c.items()):
    print('   ', cat, act, n)
print('  失败条数:', sum(1 for r in rows if not r['ok']))
print('  操作人分布:', dict(collections.Counter(r['operator'] for r in rows)))
print('  --- 最近 12 条 ---')
for r in rows[:12]:
    print('   ', r['category'], r['action'], '|', r['targetName'] or '-', '|', (r['detail'] or '')[:70],
          '| by', r['operator'], '| ok=', r['ok'])
PY

say "分类过滤"
for cat in DEVICE ENERGY ALARM REPORT SYSTEM MANUAL USER; do
  n=$(curl -s --max-time 10 -b $CK "$BASE/api/audit?from=2026-10-01&to=2026-11-30&category=$cat&limit=500" \
      | python -c "import json,sys;print(len(json.load(sys.stdin).get('rows',[])))")
  echo "  $cat: $n 条"
done

say "关键词搜索"
echo "  keyword=dh24: $(curl -s --max-time 10 -b $CK "$BASE/api/audit?from=2026-10-01&to=2026-11-30&keyword=dh24&limit=500" | python -c "import json,sys;print(len(json.load(sys.stdin).get('rows',[])))") 条"

say "权限：非管理员不能读审计"
curl -s --max-time 8 -c $CK2 -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"dh24_probe","password":"Dh24Probe2026"}' -o /dev/null -w "  probe login: %{http_code}\n"
code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -b $CK2 "$BASE/api/audit")
if [ "$code" = "403" ] || [ "$code" = "401" ]; then ok "非管理员访问 /api/audit 被拒（$code）"; else bad "非管理员竟然能读审计（$code）"; fi

say "设置项：审计保留天数"
curl -s --max-time 10 -b $CK "$BASE/api/system/settings" | python -c "
import json,sys
d=json.load(sys.stdin)
for g in d.get('groups',[]):
    for it in g.get('items',[]):
        if 'audit' in it.get('key',''):
            print('  分组:', g['name'], '| key:', it['key'], '| 默认:', it['defaultValue'], '| 范围:', it['min'], '-', it['max'])
"

say "结论"
[ $FAIL -eq 0 ] && echo "  全部通过" || echo "  有失败项"
exit $FAIL
