"""Minimal CompactDex (cdex001, из vdex019 прошивки ГУ) reader + Dalvik disassembler.
Код в vdex «quickened»: invoke-virtual-quick показывается как vtable#N.
1) debugfs -R "dump /app/X/oat/arm64/X.vdex X.vdex" product.img
2) вырезать cdex: каждый "cdex001" до конца файла (data_off — общий раздел)
usage: python3 -I cdex.py <file.cdex> list <substr>
       python3 -I cdex.py <file.cdex> sig|dump <Lclass;|prefix*> [method-substr]
"""
import struct, sys

def uleb(d, p):
    r = 0; s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s; s += 7
        if b < 0x80: return r, p

def sleb(d, p):
    r = 0; s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s; s += 7
        if b < 0x80:
            if b & 0x40: r -= 1 << s
            return r, p

class Cdex:
    def __init__(s, path):
        d = s.d = open(path, 'rb').read()
        h = struct.unpack_from('<' + 'I' * 20, d, 0x20)
        (s.file_size, _, _, _, _, s.map_off, sn, so, tn, to, pn, po, fn, fo, mn, mo, cn, co, s.data_size, s.data_off) = h
        s.db = s.data_off
        s.strings = []
        for i in range(sn):
            off = struct.unpack_from('<I', d, so + 4 * i)[0] + s.db
            _, p = uleb(d, off)
            e = d.index(b'\0', p)
            s.strings.append(d[p:e].decode('utf-8', 'replace'))
        s.types = [s.strings[struct.unpack_from('<I', d, to + 4 * i)[0]] for i in range(tn)]
        s.protos = []
        for i in range(pn):
            shorty, ret, par = struct.unpack_from('<III', d, po + 12 * i)
            ps = []
            if par:
                o = par + s.db
                n = struct.unpack_from('<I', d, o)[0]
                ps = [s.types[struct.unpack_from('<H', d, o + 4 + 2 * k)[0]] for k in range(n)]
            s.protos.append((s.types[ret], ps))
        s.fields = []
        for i in range(fn):
            c, t, nm = struct.unpack_from('<HHI', d, fo + 8 * i)
            s.fields.append((s.types[c], s.types[t], s.strings[nm]))
        s.methods = []
        for i in range(mn):
            c, p, nm = struct.unpack_from('<HHI', d, mo + 8 * i)
            s.methods.append((s.types[c], s.protos[p], s.strings[nm]))
        s.classes = []
        for i in range(cn):
            v = struct.unpack_from('<8I', d, co + 32 * i)
            s.classes.append(v)

    def mstr(s, i):
        c, (r, ps), n = s.methods[i]
        return f'{c}->{n}({"".join(ps)}){r}'

    def fstr(s, i):
        c, t, n = s.fields[i]
        return f'{c}->{n}:{t}'

    def enc_value(s, p):
        d = s.d
        b = d[p]; p += 1
        t = b & 0x1f; a = b >> 5
        if t in (0x1e,): return None, p
        if t == 0x1f: return bool(a), p
        if t == 0x1c:
            n, p = uleb(d, p); out = []
            for _ in range(n):
                v, p = s.enc_value(p); out.append(v)
            return out, p
        if t == 0x1d:
            ti, p = uleb(d, p); n, p = uleb(d, p); out = {}
            for _ in range(n):
                k, p = uleb(d, p); v, p = s.enc_value(p); out[s.strings[k]] = v
            return ('@' + s.types[ti], out), p
        raw = d[p:p + a + 1]; p += a + 1
        val = int.from_bytes(raw, 'little')
        if t in (0, 2, 4, 6):  # signed
            bits = 8 * len(raw)
            if val >> (bits - 1): val -= 1 << bits
        if t == 0x17: return repr(s.strings[val]), p
        if t == 0x18: return s.types[val], p
        if t in (0x19, 0x1b): return s.fstr(val), p
        if t == 0x1a: return s.mstr(val), p
        if t == 0x10: return struct.unpack('<f', (raw + b'\0' * 4)[-4:] if False else raw.rjust(4, b'\0')[-4:] if len(raw) < 4 else raw)[0], p
        return val, p

    def class_data(s, ci):
        cls, acc, sup, ifs, src, ann, cdo, sv = s.classes[ci]
        d = s.d
        res = dict(name=s.types[cls], super=s.types[sup] if sup != 0xffffffff else None,
                   ifaces=[], sfields=[], ifields=[], dmethods=[], vmethods=[], statics=[])
        if ifs:
            o = ifs + s.db; n = struct.unpack_from('<I', d, o)[0]
            res['ifaces'] = [s.types[struct.unpack_from('<H', d, o + 4 + 2 * k)[0]] for k in range(n)]
        if sv:
            res['statics'], _ = s.enc_value_array(sv + s.db)
        if not cdo: return res
        p = cdo + s.db
        ns, p = uleb(d, p); ni, p = uleb(d, p); nd, p = uleb(d, p); nv, p = uleb(d, p)
        for key, n in (('sfields', ns), ('ifields', ni)):
            idx = 0
            for _ in range(n):
                diff, p = uleb(d, p); idx += diff; a, p = uleb(d, p)
                res[key].append(idx)
        for key, n in (('dmethods', nd), ('vmethods', nv)):
            idx = 0
            for _ in range(n):
                diff, p = uleb(d, p); idx += diff; a, p = uleb(d, p); code, p = uleb(d, p)
                res[key].append((idx, a, code))
        return res

    def enc_value_array(s, p):
        n, p = uleb(s.d, p); out = []
        for _ in range(n):
            v, p = s.enc_value(p); out.append(v)
        return out, p

    def code(s, off):
        d = s.d
        o = off + s.db
        fields, ic = struct.unpack_from('<HH', d, o)
        regs = fields >> 12; ins = (fields >> 8) & 0xf; outs = (fields >> 4) & 0xf; tries = fields & 0xf
        n = ic >> 5; flags = ic & 0x1f
        pre = o
        if flags & 0x10:
            pre -= 2; n += struct.unpack_from('<H', d, pre)[0]
            pre -= 2; n += struct.unpack_from('<H', d, pre)[0] << 16
        if flags & 1: pre -= 2; regs += struct.unpack_from('<H', d, pre)[0]
        if flags & 2: pre -= 2; ins += struct.unpack_from('<H', d, pre)[0]
        if flags & 4: pre -= 2; outs += struct.unpack_from('<H', d, pre)[0]
        if flags & 8: pre -= 2; tries += struct.unpack_from('<H', d, pre)[0]
        regs += ins
        insns = list(struct.unpack_from('<%dH' % n, d, o + 4))
        return regs, ins, insns

# ---- Dalvik disassembler ----
FMT = {}
def F(fmt, ops):
    for op in ops: FMT[op] = fmt
NAMES = """nop move move/from16 move/16 move-wide move-wide/from16 move-wide/16 move-object move-object/from16 move-object/16
move-result move-result-wide move-result-object move-exception return-void return return-wide return-object const/4 const/16
const const/high16 const-wide/16 const-wide/32 const-wide const-wide/high16 const-string const-string/jumbo const-class monitor-enter
monitor-exit check-cast instance-of array-length new-instance new-array filled-new-array filled-new-array/range fill-array-data throw
goto goto/16 goto/32 packed-switch sparse-switch cmpl-float cmpg-float cmpl-double cmpg-double cmp-long
if-eq if-ne if-lt if-ge if-gt if-le if-eqz if-nez if-ltz if-gez if-gtz if-lez""".split()
OPN = {i: n for i, n in enumerate(NAMES)}
for i, n in enumerate("aget aget-wide aget-object aget-boolean aget-byte aget-char aget-short aput aput-wide aput-object aput-boolean aput-byte aput-char aput-short".split()):
    OPN[0x44 + i] = n
for i, n in enumerate("iget iget-wide iget-object iget-boolean iget-byte iget-char iget-short iput iput-wide iput-object iput-boolean iput-byte iput-char iput-short".split()):
    OPN[0x52 + i] = n
for i, n in enumerate("sget sget-wide sget-object sget-boolean sget-byte sget-char sget-short sput sput-wide sput-object sput-boolean sput-byte sput-char sput-short".split()):
    OPN[0x60 + i] = n
for i, n in enumerate("invoke-virtual invoke-super invoke-direct invoke-static invoke-interface".split()):
    OPN[0x6e + i] = n; OPN[0x74 + i] = n + '/range'
for op in range(0x7b, 0x90): OPN[op] = 'unop%02x' % op
for op in range(0x90, 0xe3): OPN[op] = 'binop%02x' % op
OPN[0xfa] = 'invoke-polymorphic'; OPN[0xfc] = 'invoke-custom'
F('10x', [0x00, 0x0e]); F('12x', [0x01, 0x04, 0x07, 0x21] + list(range(0x7b, 0x90)) + list(range(0xb0, 0xd0)))
F('22x', [0x02, 0x05, 0x08]); F('32x', [0x03, 0x06, 0x09]); F('11x', [0x0a, 0x0b, 0x0c, 0x0d, 0x0f, 0x10, 0x11, 0x1d, 0x1e, 0x27])
F('11n', [0x12]); F('21s', [0x13, 0x16]); F('31i', [0x14, 0x17]); F('21h', [0x15, 0x19]); F('51l', [0x18])
F('21c', [0x1a, 0x1c, 0x1f, 0x22] + list(range(0x60, 0x6e))); F('31c', [0x1b]); F('22c', [0x20, 0x23] + list(range(0x52, 0x60)))
F('35c', [0x24, 0x6e, 0x6f, 0x70, 0x71, 0x72, 0xfc]); F('3rc', [0x25, 0x74, 0x75, 0x76, 0x77, 0x78, 0xfd]); F('31t', [0x26, 0x2b, 0x2c])
F('10t', [0x28]); F('20t', [0x29]); F('30t', [0x2a]); F('23x', list(range(0x2d, 0x32)) + list(range(0x44, 0x52)) + list(range(0x90, 0xb0)))
F('22t', list(range(0x32, 0x38))); F('21t', list(range(0x38, 0x3e))); F('22s', list(range(0xd0, 0xd8))); F('22b', list(range(0xd8, 0xe3)))
F('45cc', [0xfa]); F('4rcc', [0xfb])
F('22c', list(range(0xe3,0xe9))+list(range(0xeb,0xf3))); F('35c',[0xe9]); F('3rc',[0xea]); F('10x',[0x73])
OPN[0xe9]='invoke-virtual-quick'; OPN[0xea]='invoke-virtual-quick/range'; OPN[0x73]='return-void-nb'
for op in list(range(0xe3,0xe9))+list(range(0xeb,0xf3)): OPN[op]='ifield-quick%02x'%op
SIZE = {'10x': 1, '12x': 1, '11n': 1, '11x': 1, '10t': 1, '20t': 2, '22x': 2, '21t': 2, '21s': 2, '21h': 2, '21c': 2, '23x': 2, '22b': 2,
        '22t': 2, '22s': 2, '22c': 2, '30t': 3, '32x': 3, '31i': 3, '31t': 3, '31c': 3, '35c': 3, '3rc': 3, '45cc': 4, '4rcc': 4, '51l': 5}

def ref(dx, op, idx):
    if op in (0x1a, 0x1b): return repr(dx.strings[idx])
    if op in (0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25): return dx.types[idx]
    if 0x52 <= op <= 0x6d: return dx.fstr(idx)
    if op in (0xe9,0xea): return 'vtable#%d'%idx
    if 0xe3<=op<=0xf2: return 'off#%d'%idx
    if op in (0x6e, 0x6f, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78, 0xfa, 0xfb): return dx.mstr(idx)
    return '#%d' % idx

def disasm(dx, insns):
    out = []; pc = 0; n = len(insns)
    while pc < n:
        w = insns[pc]; op = w & 0xff
        if op == 0 and w != 0:  # payloads
            ident = w
            if ident == 0x100: size = insns[pc + 1]; out.append(f'{pc:04x}: packed-switch-payload n={size}'); pc += size * 2 + 4; continue
            if ident == 0x200: size = insns[pc + 1]; keys = [insns[pc+2+2*i] | insns[pc+3+2*i] << 16 for i in range(size)]
            if ident == 0x200: out.append(f'{pc:04x}: sparse-switch-payload keys={keys}'); pc += size * 4 + 2; continue
            if ident == 0x300: width = insns[pc + 1]; size = insns[pc + 2] | insns[pc + 3] << 16; pc += (size * width + 1) // 2 + 4; out.append(f'{pc:04x}: array-payload'); continue
        f = FMT.get(op)
        if f is None: out.append(f'{pc:04x}: ?? {w:04x}'); pc += 1; continue
        nm = OPN.get(op, 'op%02x' % op); A = w >> 8; t = ''
        a1 = insns[pc + 1] if pc + 1 < n else 0; a2 = insns[pc + 2] if pc + 2 < n else 0
        if f == '10x': t = ''
        elif f == '12x': t = f'v{A & 0xf}, v{A >> 4}'
        elif f == '11n': v = A >> 4; v = v - 16 if v > 7 else v; t = f'v{A & 0xf}, #{v}'
        elif f == '11x': t = f'v{A}'
        elif f == '10t': v = A - 256 if A > 127 else A; t = f'->{pc + v:04x}'
        elif f == '20t': v = a1 - 65536 if a1 > 32767 else a1; t = f'->{pc + v:04x}'
        elif f == '22x': t = f'v{A}, v{a1}'
        elif f in ('21t',): v = a1 - 65536 if a1 > 32767 else a1; t = f'v{A}, ->{pc + v:04x}'
        elif f == '21s': v = a1 - 65536 if a1 > 32767 else a1; t = f'v{A}, #{v}'
        elif f == '21h': t = f'v{A}, #0x{a1:04x}0000'
        elif f == '21c': t = f'v{A}, {ref(dx, op, a1)}'
        elif f == '23x': t = f'v{A}, v{a1 & 0xff}, v{a1 >> 8}'
        elif f == '22b': v = a1 >> 8; v = v - 256 if v > 127 else v; t = f'v{A}, v{a1 & 0xff}, #{v}'
        elif f == '22t': v = a1 - 65536 if a1 > 32767 else a1; t = f'v{A & 0xf}, v{A >> 4}, ->{pc + v:04x}'
        elif f == '22s': v = a1 - 65536 if a1 > 32767 else a1; t = f'v{A & 0xf}, v{A >> 4}, #{v}'
        elif f == '22c': t = f'v{A & 0xf}, v{A >> 4}, {ref(dx, op, a1)}'
        elif f in ('30t', '31i', '31t', '31c'):
            v = a1 | a2 << 16
            if f != '31c' and v >= 1 << 31: v -= 1 << 32
            t = f'v{A}, ' + (ref(dx, op, v) if f == '31c' else (f'->{pc + v:04x}' if f == '31t' else f'#{v}'))
        elif f == '32x': t = f'v{a1}, v{a2}'
        elif f in ('35c', '45cc'):
            cnt = A >> 4; g = A & 0xf; regs = [a2 & 0xf, (a2 >> 4) & 0xf, (a2 >> 8) & 0xf, (a2 >> 12) & 0xf, g][:cnt]
            t = '{' + ', '.join(f'v{r}' for r in regs) + '}, ' + ref(dx, op, a1)
        elif f in ('3rc', '4rcc'): t = f'{{v{a2} .. v{a2 + A - 1}}}, ' + ref(dx, op, a1)
        elif f == '51l': v = sum(insns[pc + 1 + i] << (16 * i) for i in range(4)); t = f'v{A}, #{v}'
        out.append(f'{pc:04x}: {nm} {t}')
        pc += SIZE[f]
    return out

if __name__ == '__main__':
    dx = Cdex(sys.argv[1]); cmd = sys.argv[2]; q = sys.argv[3]
    if cmd == 'list':
        for i in range(len(dx.classes)):
            nm = dx.types[dx.classes[i][0]]
            if q in nm: print(i, nm)
    elif cmd in ('dump', 'sig'):
        mq = sys.argv[4] if len(sys.argv) > 4 else None
        for i in range(len(dx.classes)):
            nm = dx.types[dx.classes[i][0]]
            if q != nm and not (q.endswith('*') and nm.startswith(q[:-1])): continue
            c = dx.class_data(i)
            print('=== class', nm, 'extends', c['super'], 'implements', c['ifaces'])
            for k, fi in enumerate(c['sfields']):
                val = c['statics'][k] if k < len(c['statics']) else ''
                print('  static', dx.fstr(fi), '=', val)
            for fi in c['ifields']: print('  field', dx.fstr(fi))
            for mi, acc, code in c['dmethods'] + c['vmethods']:
                if mq and mq not in dx.methods[mi][2]: continue
                print('  method', dx.mstr(mi), hex(acc))
                if cmd == 'dump' and code:
                    regs, ins, insns = dx.code(code)
                    print(f'    regs={regs} ins={ins}')
                    for l in disasm(dx, insns): print('     ', l)
