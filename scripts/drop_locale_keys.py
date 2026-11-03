"""
按**完整路径**从 locale 文件里删除键。

为什么不用简单的文本替换：locale 里同名键出现在很多块里
（`save` / `close` / `name` / `status` 每个块都有一份），
按文本删会误伤别的块。必须先定位块，再在块内删。

两种形态都要处理：
1. 多行块：`ast: {` 换行若干子键 换行 `},`
2. 单行内联对象：`ast: { active: 'x', acked: 'y' },`

保留原文件的行尾风格（CRLF/LF）与缩进。
"""
import re


def _indent_of(line):
    return len(line) - len(line.lstrip())


def _find_block(lines, name, indent):
    """找 `indent` 缩进下的 `name:` 键（值是对象）。

    返回 (start, end_exclusive, inline)。inline=True 表示值在同一行。
    """
    pat = re.compile(r'^\s{%d}%s:' % (indent, re.escape(name)))
    for i, ln in enumerate(lines):
        if not pat.match(ln):
            continue
        rest = ln[ln.index(':') + 1:].strip()
        if rest.startswith('{'):
            if rest.rstrip().endswith('}') and rest.count('{') == rest.count('}'):
                # 单行内联对象
                return i, i + 1, True
            # 多行块：找到缩进相同且以 } 结尾的行
            j = i + 1
            while j < len(lines):
                st = lines[j].strip()
                if _indent_of(lines[j]) == indent and (st == '},' or st == '}'):
                    return i, j + 1, False
                j += 1
            return i, j, False
        # 标量值
        return i, i + 1, False
    return None


def _find_key(lines, start, end, name, indent):
    """在 [start, end) 内找 `indent` 缩进的 `name:` 键。

    返回 (key_line, value_end_line_exclusive, inline_obj_str_or_None)
    inline_obj_str 是"内联对象里要删的那部分"（含前后逗号）。
    """
    pat = re.compile(r'^\s{%d}%s:' % (indent, re.escape(name)))
    for i in range(start, end):
        if not pat.match(lines[i]):
            continue
        colon = lines[i].index(':')
        rest = lines[i][colon + 1:].strip()
        if rest.startswith('{'):
            if rest.rstrip().endswith('}') and rest.count('{') == rest.count('}'):
                return i, i + 1, lines[i]
            j = i + 1
            while j < end:
                st = lines[j].strip()
                if _indent_of(lines[j]) == indent and (st == '},' or st == '}'):
                    return i, j + 1, None
                j += 1
            return i, j, None
        return i, i + 1, None
    return None


def _strip_inline_key(line, name):
    """从单行内联对象里去掉 `name: '...',` 这一项。返回新行（None 表示整行没了）。"""
    m = re.match(r'^(\s*)([A-Za-z_][A-Za-z0-9_]*):\s*\{(.*)\}(\s*,?)\s*$', line)
    if not m:
        return line
    indent, key, body, tail = m.groups()
    if key != name:
        return line
    # body 形如 " active: 'x', acked: 'y' " 或 " active: 'x' "
    items = []
    depth = 0
    cur = ''
    in_str = False
    quote = ''
    for ch in body:
        if in_str:
            cur += ch
            if ch == quote:
                in_str = False
            continue
        if ch in ('\'', '"'):
            in_str = True
            quote = ch
            cur += ch
            continue
        if ch in '{[(':
            depth += 1
        elif ch in '}])':
            depth -= 1
        if ch == ',' and depth == 0:
            items.append(cur)
            cur = ''
            continue
        cur += ch
    if cur.strip():
        items.append(cur)
    items = [x.strip() for x in items if x.strip()]
    kept = [x for x in items if not re.match(r'^%s:' % re.escape(name), x)]
    if len(kept) == len(items):
        return line
    if not kept:
        return None
    return '%s%s: { %s }%s' % (indent, key, ', '.join(kept), tail)


def drop_key_path(lines, path, top_indent=2):
    """删除 `a.b` 形式的键。返回 (新行列表, 是否删成功)。"""
    parts = path.split('.')
    # 逐层下降直到最后一段
    cur_start, cur_end = 0, len(lines)
    cur_indent = top_indent
    for part in parts[:-1]:
        blk = _find_block(lines, part, cur_indent)
        if blk is None:
            return lines, False
        s0, e0, inline = blk
        if inline:
            return lines, False   # 内联对象里再嵌一层，本项目没有这种
        cur_start, cur_end = s0, e0
        cur_indent += 2
    # 最后一段
    last = parts[-1]
    if len(parts) == 1:
        blk = _find_block(lines, last, top_indent)
        if blk is None:
            return lines, False
        s0, e0, _ = blk
        return lines[:s0] + lines[e0:], True
    kv = _find_key(lines, cur_start, cur_end, last, cur_indent)
    if kv is None:
        return lines, False
    k0, k1, inline = kv
    if inline is not None:
        new_line = _strip_inline_key(lines[k0], last)
        if new_line is None:
            return lines[:k0] + lines[k1:], True
        out = list(lines)
        out[k0] = new_line
        return out, True
    return lines[:k0] + lines[k1:], True


def drop_keys(path, paths):
    """在文件里删多个键路径。返回成功删除的键列表。"""
    with open(path, 'rb') as f:
        raw = f.read().decode('utf-8')
    eol = '\r\n' if '\r\n' in raw else '\n'
    lines = raw.split(eol)
    ok = []
    for p in paths:
        lines, done = drop_key_path(lines, p)
        if done:
            ok.append(p)
    with open(path, 'wb') as f:
        f.write(eol.join(lines).encode('utf-8'))
    return ok
