#!/usr/bin/env python3
"""
Чтение приборки (QNX) с ПК через adb — для поиска байта температуры ОЖ.

Как это устроено:
  ПК --adb--> ГУ (Android) --nc 192.168.2.1:8000--> qconn приборки (QNX)
  qconn — служба удалённой отладки QNX. Команды «service launcher» →
  «start/flags 8000 <путь> <argv>» запускают процесс на приборке, его
  stdin/stdout идут по этому же сокету. Запускаем sh и даём ему команды.

  Данные лежат в PPS (/var/pps/...) — это не поток сообщений, а «объекты-
  файлы»: сервис (spi_service — данные от MCU по SPI) перезаписывает в
  объекте атрибуты, `cat` читает текущий снимок:
      @a000
      data:json:{"...","data":[12,0,255,...]}
  Если открыть объект как «a000?wait,delta», чтение блокируется до
  изменения и отдаёт только изменившиеся атрибуты — получается поток.
  Специальный объект «.all» в каталоге отдаёт изменения всех объектов.

Режимы:
  tools/cluster_pps.py probe            есть ли nc на ГУ, отвечает ли qconn, где sh
  tools/cluster_pps.py explore          что есть в /var/pps + снимок всех объектов spi_service
  tools/cluster_pps.py watch [мин]      поток изменений (?wait,delta), по умолчанию 30 мин
  tools/cluster_pps.py poll [мин] [с]   снимки объектов каждые [с] секунд (если watch не идёт)

Опции (после режима):
  --objects a000,a0ff,a5a5   какие объекты (по умолчанию: watch — .all, poll — все)
  --dir /var/pps/spi_service каталог PPS
  --sh /bin/sh               путь sh на приборке (иначе ищем)

Во время watch/poll можно печатать заметку и нажимать Enter — она попадёт
в лог с временем (например «приборка 65» или «завёл двигатель»). По этим
меткам найдём байт, который меняется вместе с температурой. Ctrl+C — стоп.

Лог: logs/cluster_<режим>_<дата>.txt. Для каждого изменения массива data
пишется, какие байты поменялись: «a000 b10: 113→114».

ТОЛЬКО ЧТЕНИЕ: в приборку ничего не пишем.
"""

import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from datetime import datetime

HOST = "192.168.2.1"
PORT = 8000
SH_DIRS = ["/bin", "/proc/boot", "/usr/bin", "/sbin", "/usr/sbin",
           "/system/bin", "/xbin", "/usr/local/bin", "/mnt/bin", "/np/bin"]
PATH = ":".join(SH_DIRS)
STDIN_DELAY = 1.3  # раньше процесс теряет stdin (как у Jarvis)


def find_adb():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    for c in ["adb",
              os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools/adb"),
              os.path.join(os.environ.get("ANDROID_SDK_ROOT", ""), "platform-tools/adb"),
              os.path.expanduser("~/Android/Sdk/platform-tools/adb")]:
        p = shutil.which(c) or (c if os.path.isfile(c) and os.access(c, os.X_OK) else None)
        if p:
            return p
    sys.exit("adb не найден: задайте ADB=/путь/к/adb")


ADB = find_adb()


def adb_sh(cmd, timeout=15):
    r = subprocess.run([ADB, "shell", cmd], stdin=subprocess.DEVNULL,
                       capture_output=True, timeout=timeout)
    return r.returncode, (r.stdout + r.stderr).decode("utf-8", "replace")


def find_nc():
    """Команда netcat на ГУ: nc, toybox nc или busybox nc."""
    for nc in ["nc", "toybox nc", "busybox nc", "/system/bin/busybox nc", "/system/xbin/busybox nc"]:
        code, out = adb_sh(f"{nc} --help 2>&1; echo rc=$?")
        low = out.lower()
        if "not found" in low or "inaccessible" in low or "applet not found" in low or "unknown" in low:
            continue
        if "usage" in low or "nc" in low:
            return nc
    return None


class Tunnel:
    """Сокет до qconn приборки через `adb shell nc`."""

    def __init__(self, nc):
        self.p = subprocess.Popen(
            [ADB, "shell", "-T", f"{nc} {HOST} {PORT}"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            bufsize=0)
        self.q = queue.Queue()
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        while True:
            b = self.p.stdout.read(65536)
            if not b:
                self.q.put(None)
                return
            self.q.put(b)

    def send(self, s):
        try:
            self.p.stdin.write(s.encode("ascii"))
            self.p.stdin.flush()
        except (BrokenPipeError, OSError):
            pass

    def read(self, timeout):
        """Кусок данных, b'' — таймаут, None — соединение закрыто."""
        try:
            return self.q.get(timeout=timeout)
        except queue.Empty:
            return b""

    def close(self):
        try:
            self.p.stdin.close()
        except OSError:
            pass
        try:
            self.p.terminate()
            self.p.wait(3)
        except Exception:
            self.p.kill()


def strip_telnet(data):
    """Убрать telnet-последовательности IAC (255 + 2 байта)."""
    out = bytearray()
    i = 0
    while i < len(data):
        if data[i] == 255:
            i += 3
            continue
        out.append(data[i])
        i += 1
    return bytes(out)


def launch(nc, path, argv):
    t = Tunnel(nc)
    t.send("service launcher\n")
    t.send("chdir /\n")
    t.send(f"start/flags 8000 {path} {' '.join(argv)}\n")
    t.send("continue\n")
    return t


def run_once(nc, path, argv, stdin=None, timeout=15):
    """Запустить процесс, дождаться конца вывода. Возвращает текст."""
    t = launch(nc, path, argv)
    if stdin is not None:
        time.sleep(STDIN_DELAY)
        t.send(stdin)
    buf = bytearray()
    deadline = time.time() + timeout
    while time.time() < deadline:
        b = t.read(0.5)
        if b is None:
            break
        buf += b
    t.close()
    return strip_telnet(bytes(buf)).decode("latin-1")


def not_found(text):
    low = text.lower()
    return "no such file" in low or "error no such" in low or "command not found" in low


def find_sh(nc, override):
    if override:
        return override
    for d in SH_DIRS:
        path = f"{d}/sh"
        out = run_once(nc, path, ["sh", "-c", "exit"], timeout=6)
        if not not_found(out):
            return path
    return None


# --- Лог и разбор ---

class Log:
    def __init__(self, mode):
        os.makedirs("logs", exist_ok=True)
        self.path = f"logs/cluster_{mode}_{datetime.now():%Y%m%d_%H%M%S}.txt"
        self.f = open(self.path, "w", encoding="utf-8")
        self.lock = threading.Lock()

    def __call__(self, text, echo=True):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        with self.lock:
            for line in text.splitlines() or [""]:
                self.f.write(f"{ts} {line}\n")
                if echo:
                    print(f"{ts} {line}", flush=True)
            self.f.flush()


DATA_RE = re.compile(r'"data"\s*:\s*\[([^\]]*)\]')


def parse_bytes(line):
    m = DATA_RE.search(line)
    if not m:
        return None
    try:
        return [int(x) & 0xFF for x in m.group(1).split(",") if x.strip()]
    except ValueError:
        return None


class Differ:
    """Помнит последние байты каждого объекта, пишет изменившиеся."""

    def __init__(self, log):
        self.log = log
        self.prev = {}
        self.obj = "?"

    def line(self, line):
        line = line.rstrip("\r")
        if not line:
            return
        if line.startswith("@"):
            self.obj = line[1:].strip()
            self.log(line, echo=False)
            return
        self.log(f"[{self.obj}] {line}", echo=False)
        b = parse_bytes(line)
        if b is None:
            return
        attr = line.split(":", 1)[0]
        key = f"{self.obj}.{attr}"
        old = self.prev.get(key)
        self.prev[key] = b
        if old is None:
            self.log(f"{self.obj} ({len(b)} байт): {' '.join(f'{i}={v}' for i, v in enumerate(b))}")
            return
        ch = [f"b{i}: {old[i] if i < len(old) else '-'}→{b[i] if i < len(b) else '-'}"
              for i in range(max(len(old), len(b)))
              if (old[i] if i < len(old) else None) != (b[i] if i < len(b) else None)]
        if ch:
            self.log(f"{self.obj} " + ", ".join(ch))


def notes(log, stop):
    """Заметки пользователя из консоли → метки в логе."""
    while not stop.is_set():
        try:
            s = sys.stdin.readline()
        except Exception:
            return
        if not s:
            return
        if s.strip():
            log(f"### МЕТКА: {s.strip()}")


def stream(nc, sh, mode, script, minutes, stop_cmd):
    log = Log(mode)
    print(f"Лог: {log.path}\nПечатайте заметку + Enter, чтобы поставить метку. Ctrl+C — стоп.", flush=True)
    t = launch(nc, sh, ["sh"])
    time.sleep(STDIN_DELAY)
    t.send(f"PATH={PATH}; export PATH\n")
    t.send(script + "\n")
    log(f"--- {mode}: {script}")
    differ = Differ(log)
    stop = threading.Event()
    threading.Thread(target=notes, args=(log, stop), daemon=True).start()
    end = time.time() + minutes * 60
    tail = b""
    last_data = time.time()
    try:
        while time.time() < end:
            b = t.read(1.0)
            if b is None:
                log("--- соединение закрыто")
                break
            if not b:
                if time.time() - last_data > 120:
                    log("--- 2 минуты без данных")
                    last_data = time.time()
                continue
            last_data = time.time()
            tail += strip_telnet(b)
            *lines, tail = tail.split(b"\n")
            for ln in lines:
                differ.line(ln.decode("latin-1"))
    except KeyboardInterrupt:
        log("--- стоп (Ctrl+C)")
    finally:
        stop.set()
        t.send(stop_cmd)
        time.sleep(1)
        t.close()
        print(f"Лог сохранён: {log.path}")


def main():
    args = sys.argv[1:]
    opts = {}
    pos = []
    i = 0
    while i < len(args):
        if args[i].startswith("--") and i + 1 < len(args):
            opts[args[i][2:]] = args[i + 1]
            i += 2
        else:
            pos.append(args[i])
            i += 1
    mode = pos[0] if pos else "probe"
    pps_dir = opts.get("dir", "/var/pps/spi_service")
    objects = [o for o in opts.get("objects", "").split(",") if o]

    code, out = adb_sh("echo ok")
    if "ok" not in out:
        sys.exit(f"adb не видит ГУ: {out.strip()}")
    nc = find_nc()
    if not nc:
        sys.exit("на ГУ нет nc (пробовал nc, toybox nc, busybox nc)")
    print(f"nc на ГУ: {nc}")
    code, out = adb_sh(f"ping -c 1 -W 2 {HOST} >/dev/null 2>&1 && echo up || echo down")
    print(f"ping {HOST}: {out.strip()}")
    sh = find_sh(nc, opts.get("sh"))
    if not sh:
        sys.exit(f"qconn {HOST}:{PORT} не запускает sh (нет ответа или sh не найден)")
    print(f"sh на приборке: {sh}")

    if mode == "probe":
        print(run_once(nc, sh, ["sh"], f"PATH={PATH}; export PATH; uname -a; ls -l {pps_dir}; exit\n"))
    elif mode == "explore":
        log = Log("explore")
        script = (f"PATH={PATH}; export PATH; uname -a; echo; ls -l /var/pps; echo; ls -lR /var/pps; "
                  f"for f in {pps_dir}/*; do echo; echo \"=== $f\"; cat \"$f\"; done; exit\n")
        log(run_once(nc, sh, ["sh"], script, timeout=60), echo=False)
        print(f"Лог: {log.path}")
    elif mode == "watch":
        minutes = float(pos[1]) if len(pos) > 1 else 30
        targets = objects or [".all"]
        cats = " ".join(f"cat '{pps_dir}/{o}?wait,delta' & p=\"$p $!\";" for o in targets)
        stream(nc, sh, "watch", f"p=; {cats}", minutes, "kill $p; exit\n")
    elif mode == "poll":
        minutes = float(pos[1]) if len(pos) > 1 else 30
        every = int(pos[2]) if len(pos) > 2 else 2
        files = " ".join(f"{pps_dir}/{o}" for o in objects) if objects else f"{pps_dir}/*"
        loop = f"(while :; do for f in {files}; do cat \"$f\"; done; sleep {every} || exit; done) & p=$!"
        stream(nc, sh, "poll", loop, minutes, "kill $p; exit\n")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
