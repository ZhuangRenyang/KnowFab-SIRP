# -*- coding: utf-8 -*-
"""DH-24 审计接入完整性核查（结构层）。

对每个写接口（POST/PUT/DELETE）判定：
  AUDITED   —— 方法体内有 audit.ok / audit.fail 调用
  NO-AUDIT  —— 方法体内没有（需要人工确认是否「有意排除」）
另外检查分层：控制器是否越过 service 直连 repository。
"""
import io
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'java', 'com', 'factory', 'hub')

MAPPING = re.compile(r'@(Post|Put|Delete)Mapping')
CLASS_REQ = re.compile(r'@RequestMapping\(\s*"([^"]*)"')
METHOD_NAME = re.compile(r'public\s+[\w<>,\[\]\s\.]+?\s+(\w+)\s*\(')
AUDIT_CALL = re.compile(r'\baudit\.(ok|fail)\s*\(')


def java_files(sub):
    d = os.path.join(ROOT, sub)
    out = []
    for dp, _, fns in os.walk(d):
        for fn in fns:
            if fn.endswith('.java'):
                out.append(os.path.join(dp, fn))
    return sorted(out)


def helper_audit_names(src):
    """找出类里所有「内部含 audit 调用」的私有方法名。
    审计不一定写在 endpoint 方法体里——像登录失败走 auditLoginFail() 助手，
    只看方法体会误判成没接审计。"""
    names = set()
    for m in re.finditer(r'private\s+[\w<>,\[\]\s\.]+?\s+(\w+)\s*\([^)]*\)\s*\{', src):
        name = m.group(1)
        # 从方法体起点往后取，直到大括号配平
        i = src.index('{', m.end() - 1)
        depth = 0
        j = i
        while j < len(src):
            if src[j] == '{':
                depth += 1
            elif src[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if AUDIT_CALL.search(src[i:j]):
            names.add(name)
    return names


def parse_controller(path):
    """返回 [(method_name, http_verb, path)] 以及类级 @RequestMapping。"""
    src = io.open(path, encoding='utf-8').read()
    lines = src.split('\n')
    base = ''
    m = CLASS_REQ.search(src)
    if m:
        base = m.group(1)
    helpers = helper_audit_names(src)
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        mm = MAPPING.search(line)
        if not mm:
            i += 1
            continue
        verb = mm.group(1).upper()
        path = ''
        pm = re.search(r'"\s*/?([^"]*)"', line)
        if pm:
            path = pm.group(1)
        # 往后找方法签名和方法体结束
        j = i + 1
        sig = ''
        while j < len(lines) and j < i + 12:
            sig += ' ' + lines[j].strip()
            if '(' in sig and ')' in sig:
                break
            j += 1
        nm = METHOD_NAME.search(sig)
        name = nm.group(1) if nm else '?'
        # 方法体：从 sig 的第一个 { 到配对的 }
        k = j
        while k < len(lines) and '{' not in lines[k]:
            k += 1
        depth = 0
        body = []
        while k < len(lines):
            body.append(lines[k])
            depth += lines[k].count('{') - lines[k].count('}')
            if depth <= 0 and '{' in ''.join(body):
                break
            k += 1
        text = '\n'.join(body)
        has = bool(AUDIT_CALL.search(text))
        via = ''
        if not has:
            # 通过类内助手间接审计
            for h in helpers:
                if re.search(r'\b%s\s*\(' % re.escape(h), text):
                    has = True
                    via = h
                    break
        out.append((name, verb, base + path, text, lines[i], via, has))
        i = k + 1
    return out


def main():
    print('=' * 96)
    print('DH-24 审计接入完整性核查')
    print('=' * 96)
    audited = noaudit = 0
    gaps = []
    rows = []
    for path in java_files('.'):
        if not path.endswith('Controller.java'):
            continue
        rel = os.path.relpath(path, ROOT).replace('\\', '/')
        for name, verb, url, body, decl, via, has in parse_controller(path):
            if verb not in ('POST', 'PUT', 'DELETE'):
                continue
            if has:
                audited += 1
            else:
                noaudit += 1
                gaps.append((rel, verb, url, name))
            rows.append((rel, verb, url, name, has, via))

    print('\n写接口总数 %d：有审计 %d / 无审计 %d\n' % (audited + noaudit, audited, noaudit))
    print('--- 有审计的写接口（按控制器）---')
    byc = {}
    for rel, verb, url, name, has, via in rows:
        if has:
            byc.setdefault(rel, []).append('%s %s' % (verb, url))
    for c in sorted(byc):
        print('  %-46s %d 处: %s' % (c, len(byc[c]), '; '.join(byc[c][:4]) +
                                       (' …' if len(byc[c]) > 4 else '')))
    via_helpers = [(rel, verb, url, name, v) for rel, verb, url, name, h, v in rows if v]
    if via_helpers:
        print('\n--- 通过类内助手间接审计的 ---')
        for rel, verb, url, name, v in via_helpers:
            print('  %-46s %-6s %-40s → %s()' % (rel, verb, url, v))
    print('\n--- 无审计的写接口（逐条判定是否「有意排除」）---')
    for rel, verb, url, name in gaps:
        print('  %-46s %-6s %-42s %s' % (rel, verb, url, name))

    # 分层检查：控制器是否直连 repository
    print('\n' + '=' * 96)
    print('分层检查：控制器是否越过 service 直连 repository')
    print('=' * 96)
    bad = 0
    for path in java_files('.'):
        if not path.endswith('Controller.java'):
            continue
        src = io.open(path, encoding='utf-8').read()
        rel = os.path.relpath(path, ROOT).replace('\\', '/')
        for ln in src.split('\n'):
            # 只看项目自己的 repository，排除 Spring Security 的 HttpSessionSecurityContextRepository
            if re.search(r'com\.factory\.hub\.[\w.]*Repository', ln) or \
               re.match(r'\s*private final \w*Repository\b', ln):
                print('  ~ %-46s %s' % (rel, ln.strip()[:80]))
                bad += 1
    if not bad:
        print('  通过：所有控制器只依赖 service/store，没有直连 repository')
    else:
        print('  （%d 处。这是项目既有风格：简单 CRUD 直接注入 repository，'
              '不是 DH-24 引入的）' % bad)

    # 跨层依赖：service / repository 是否反向依赖 web 层
    print('\n' + '=' * 96)
    print('反向依赖检查：非 web 包是否 import 了 web 层')
    print('=' * 96)
    rev = 0
    for path in java_files('.'):
        rel = os.path.relpath(path, ROOT).replace('\\', '/')
        if '/web/' in rel or rel.startswith('web/'):
            continue
        src = io.open(path, encoding='utf-8').read()
        for ln in src.split('\n'):
            if ln.startswith('import com.factory.hub.web.'):
                print('  ! %-46s %s' % (rel, ln.strip()[:80]))
                rev += 1
    if not rev:
        print('  通过：没有非 web 层反向依赖 web 层')


if __name__ == '__main__':
    main()
