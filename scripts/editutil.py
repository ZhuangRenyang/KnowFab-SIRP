"""小工具：按原样读写文件、保持 CRLF/LF 行尾风格、按文件实际行尾做锚点替换。

为什么需要它：
· Windows 上工作区文件可能是 CRLF，git 里是 LF。用 Python 改 .java/.vue 时
  如果按 '\n' 去匹配锚点，一个都匹配不上（行尾对不上）；
  如果用文本模式读写，会把整个文件重写成 CRLF，git 里就变成全文件改动。
· 所以：按 bytes 读、按 bytes 写，替换时把锚点里的 '\n' 适配成文件实际的行尾。
"""


def read_keep(path):
    """按原样读（保留 CRLF/LF）"""
    with open(path, 'rb') as f:
        return f.read().decode('utf-8')


def write_keep(path, s):
    """按原行尾写回（不改变 CRLF/LF 风格）"""
    with open(path, 'wb') as f:
        f.write(s.encode('utf-8'))


def eol_of(s):
    """文件实际行尾"""
    return '\r\n' if '\r\n' in s else '\n'


def edit(path, pairs):
    """对文件做一批替换。pairs = [(old, new), ...]，old 里的 \\n 自动适配行尾。

    返回实际发生的替换次数；找不到的锚点会抛 AssertionError（宁可报错也不要静默跳过）。
    """
    s = read_keep(path)
    e = eol_of(s)
    n = 0
    for old, new in pairs:
        o = old.replace('\n', e)
        if o not in s:
            raise AssertionError('%s 没找到锚点: %r' % (path, old[:90]))
        s = s.replace(o, new.replace('\n', e), 1)
        n += 1
    write_keep(path, s)
    return n
