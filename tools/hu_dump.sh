#!/usr/bin/env bash
# Сбор сведений о головном устройстве (ГУ) через adb — для исследования того,
# как читать данные автомобиля (температура ОЖ, расход, бортовой компьютер)
# и как управлять климатом, стеклоподъёмниками и т.д.
#
# ТОЛЬКО ЧТЕНИЕ: скрипт ничего не пишет в машину. Действия в сценариях
# выполняете вы сами — штатными кнопками или штатным интерфейсом ГУ.
#
# Режимы:
#   tools/hu_dump.sh static          всё о системе + бинарники прошивки (APK/jar/lib)
#   tools/hu_dump.sh scenarios       пошаговые сценарии со срезами до/после
#   tools/hu_dump.sh watch [мин] [с] срезы каждые [с] секунд [мин] минут
#                                    (прогрев двигателя, поездка — без участия)
#   tools/hu_dump.sh all             static + scenarios
#
# Переменные окружения:
#   OUT=каталог          куда писать (по умолчанию logs/hu_dump_<дата>)
#   ANDROID_SERIAL=...   если подключено несколько устройств
#   NO_BINARIES=1        static без выгрузки APK/jar/lib (быстрее, меньше места)
#
# Перед запуском: на ГУ установлен Dash (com.dash.headunit) — он делает
# срезы шины Desay SV. Машина стоит, P, ручник. Зажигание ВКЛ.

set -u

# adb: из переменной ADB, из PATH или из Android SDK.
if [ -z "${ADB:-}" ]; then
    for c in adb "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb"; do
        if command -v "$c" >/dev/null 2>&1; then ADB=$c; break; fi
    done
fi
ADB=${ADB:-adb}
MODE=${1:-all}
OUT=${OUT:-logs/hu_dump_$(date +%Y%m%d_%H%M%S)}
APP=com.dash.headunit
SNAP_DIR_DEVICE=/sdcard/Android/data/$APP/files/snapshots
# Имена, по которым ищем «автомобильные» HAL, библиотеки и пакеты.
CAR_RE='desay|chery|vehicle|automotive|hvac|mcu|carinfo|svcar|canbus|libcan|cluster|meter|tbox|carlan|carstate|carservice|android\.car|com\.android\.car|dsv'

# Все вызовы adb — без stdin: иначе adb shell съедает ответы на вопросы сценариев.
adb_() { "$ADB" "$@" < /dev/null; }

mkdir -p "$OUT"
LOG="$OUT/_hu_dump.log"

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

# adb shell с таймаутом; вывод (и ошибки) — в файл.
sh_to() {
    local file=$1; shift
    mkdir -p "$(dirname "$OUT/$file")"
    timeout "${TMO:-120}" "$ADB" shell "$@" < /dev/null > "$OUT/$file" 2>&1
    local rc=$?
    [ $rc -eq 124 ] && echo "### ТАЙМАУТ" >> "$OUT/$file"
    return 0
}

marker() {
    adb_ shell log -p i -t CLAUDE_MARK "\"$*\"" >/dev/null 2>&1
    echo "[$(date '+%F %T')] $*" >> "$OUT/markers.txt"
}

check_device() {
    if ! adb_ get-state >/dev/null 2>&1; then
        echo "Нет устройства adb. Подключите ГУ (adb devices) и повторите." >&2
        exit 1
    fi
    say "устройство: $(adb_ shell getprop ro.build.fingerprint | tr -d '\r')"
    if ! adb_ shell pm path $APP >/dev/null 2>&1; then
        say "ВНИМАНИЕ: $APP не установлен — срезов шины Desay не будет"
    fi
}

# ---------- срезы ----------

VHAL_LIST=""

find_vhals() {
    VHAL_LIST=$(adb_ shell lshal -i --neat 2>/dev/null | tr -d '\r' | awk '{print $1}' \
        | grep -iE "$CAR_RE" | sort -u)
    say "HAL автомобиля: ${VHAL_LIST:-не найдены}"
}

# Срез: шина Desay (через приложение), значения VHAL, car_service.
snapshot() {
    local label=$1
    local dir="snapshots/$label"
    mkdir -p "$OUT/$dir"
    marker "SNAPSHOT $label"
    adb_ shell rm -f "$SNAP_DIR_DEVICE/$label.done" >/dev/null 2>&1
    adb_ shell am broadcast -a $APP.CAR_SNAPSHOT -n $APP/.diag.CarSnapshotReceiver \
        --es label "$label" >/dev/null 2>&1
    # Пока приложение сканирует шину — снимаем HAL и car_service.
    local hal
    for hal in $VHAL_LIST; do
        TMO=60 sh_to "$dir/lshal_debug_$(echo "$hal" | tr '/:@' '___').txt" lshal debug "$hal"
    done
    TMO=60 sh_to "$dir/dumpsys_car_service.txt" dumpsys car_service
    local i
    for i in $(seq 1 120); do
        adb_ shell test -f "$SNAP_DIR_DEVICE/$label.done" 2>/dev/null && break
        sleep 1
    done
    adb_ pull "$SNAP_DIR_DEVICE/$label.txt" "$OUT/$dir/desay.txt" >/dev/null 2>&1 \
        || echo "нет среза Desay" > "$OUT/$dir/desay.txt"
}

# ---------- статика ----------

static_info() {
    say "=== СТАТИКА: система ==="
    sh_to system/getprop.txt getprop
    sh_to system/uname.txt "uname -a; cat /proc/version; cat /proc/cpuinfo; cat /proc/meminfo"
    sh_to system/env.txt "env"
    sh_to system/mounts.txt "mount; df -h"
    sh_to system/ps.txt "ps -A -o PID,PPID,USER,LABEL,NAME,ARGS"
    sh_to system/selinux.txt "getenforce; id"
    sh_to system/devices.txt "ls -lZ /dev /dev/socket /dev/input 2>&1; cat /proc/devices; cat /proc/bus/input/devices"
    sh_to system/net.txt "ip -d link; ip addr; cat /proc/net/dev; ls -l /proc/net/can 2>&1; cat /proc/net/can/* 2>&1"
    sh_to system/tty.txt "ls -l /dev/tty* /dev/spidev* /dev/i2c* 2>&1; ls -l /sys/class/tty /sys/bus/spi/devices 2>&1"

    say "=== СТАТИКА: пакеты, права, фичи ==="
    sh_to packages/list.txt pm list packages -f -U -i
    sh_to packages/list_system.txt pm list packages -s -f
    sh_to packages/permissions.txt pm list permissions -g -f
    sh_to packages/features.txt pm list features
    sh_to packages/libraries.txt pm list libraries
    sh_to packages/instrumentation.txt pm list instrumentation

    say "=== СТАТИКА: сервисы и HAL ==="
    sh_to services/service_list.txt service list
    sh_to services/dumpsys_list.txt dumpsys -l
    sh_to services/lshal.txt lshal list -itrpca
    sh_to services/lshal_neat.txt lshal -i --neat

    say "=== СТАТИКА: настройки ==="
    sh_to settings/system.txt settings list system
    sh_to settings/secure.txt settings list secure
    sh_to settings/global.txt settings list global

    say "=== СТАТИКА: init-скрипты и файлы ==="
    sh_to files/init_rc.txt 'for f in /init*.rc /system/etc/init/*.rc /vendor/etc/init/*.rc /vendor/etc/init/hw/*.rc /product/etc/init/*.rc /odm/etc/init/*.rc; do echo "##### $f"; cat "$f"; done'
    TMO=300 sh_to files/ls_system.txt "ls -lR /system /vendor /product /odm 2>&1"
    sh_to files/vintf.txt 'for f in /vendor/manifest.xml /vendor/etc/vintf/manifest.xml /system/etc/vintf/manifest.xml /vendor/compatibility_matrix.xml; do echo "##### $f"; cat "$f"; done'

    say "=== СТАТИКА: dumpsys всех сервисов (несколько минут) ==="
    local svc
    for svc in $(adb_ shell dumpsys -l 2>/dev/null | tr -d '\r' | tail -n +2 | awk '{print $1}'); do
        TMO=40 sh_to "dumpsys/$(echo "$svc" | tr '/' '_').txt" dumpsys "$svc"
    done
    TMO=60 sh_to dumpsys/_activity_broadcasts.txt dumpsys activity broadcasts
    TMO=60 sh_to dumpsys/_activity_services.txt dumpsys activity services
    TMO=60 sh_to dumpsys/_activity_providers.txt dumpsys activity providers
    TMO=60 sh_to dumpsys/_car_service_help.txt dumpsys car_service --help
    TMO=60 sh_to dumpsys/_car_service_hal.txt dumpsys car_service --hal

    say "=== СТАТИКА: Vehicle HAL ==="
    find_vhals
    local hal
    for hal in $VHAL_LIST; do
        TMO=60 sh_to "vhal/lshal_debug_$(echo "$hal" | tr '/:@' '___').txt" lshal debug "$hal"
    done

    say "=== СТАТИКА: конфиги из etc ==="
    local f
    mkdir -p "$OUT/files/etc"
    for f in $(adb_ shell "find /system/etc /vendor/etc /product/etc /odm/etc -type f -size -1024k 2>/dev/null" \
        | tr -d '\r' | grep -iE "$CAR_RE|car|can|vhal|prop|config|hvac|\.xml$|\.json$|\.conf$"); do
        mkdir -p "$OUT/files/etc$(dirname "$f")"
        adb_ pull "$f" "$OUT/files/etc$f" >/dev/null 2>&1
    done

    say "=== СТАТИКА: bugreport (несколько минут) ==="
    timeout 900 "$ADB" bugreport "$OUT/bugreport.zip" >> "$LOG" 2>&1 || say "bugreport не удался"

    if [ "${NO_BINARIES:-0}" != "1" ]; then
        pull_binaries
    fi
}

# APK/jar/so прошивки — для декомпиляции на ПК (jadx и т.п.).
pull_binaries() {
    say "=== БИНАРНИКИ: APK автомобильных/вендорских пакетов ==="
    mkdir -p "$OUT/bin/apps"
    local line path dir pkg
    adb_ shell pm list packages -f 2>/dev/null | tr -d '\r' | while read -r line; do
        line=${line#package:}
        path=${line%=*}
        pkg=${line##*=}
        echo "$pkg $path" | grep -qiE "$CAR_RE|/product/|/vendor/|/odm/" || continue
        dir=$(dirname "$path")
        mkdir -p "$OUT/bin/apps/$pkg"
        # Весь каталог: в /system dex часто вынесен в oat/*.vdex.
        adb_ pull "$dir" "$OUT/bin/apps/$pkg/" >/dev/null 2>&1
    done

    say "=== БИНАРНИКИ: фреймворк (/system/framework, vendor, product) ==="
    mkdir -p "$OUT/bin/framework"
    local d
    for d in /system/framework /vendor/framework /product/framework /odm/framework; do
        adb_ shell test -d "$d" 2>/dev/null || continue
        adb_ pull "$d" "$OUT/bin/framework/$(echo "$d" | tr '/' '_')" >/dev/null 2>&1
    done

    say "=== БИНАРНИКИ: HAL и библиотеки ==="
    mkdir -p "$OUT/bin/native"
    adb_ pull /vendor/bin/hw "$OUT/bin/native/vendor_bin_hw" >/dev/null 2>&1
    for f in $(adb_ shell "ls /vendor/lib64 /vendor/lib64/hw /vendor/lib /vendor/lib/hw /vendor/bin /system/lib64 /system/bin /product/lib64 2>/dev/null | grep -v ':$'" | tr -d '\r' | grep -iE "$CAR_RE|libsv|libdsv|sv[a-z]*car" | sort -u); do
        local src
        for src in /vendor/lib64/$f /vendor/lib64/hw/$f /vendor/lib/$f /vendor/lib/hw/$f /vendor/bin/$f /system/lib64/$f /system/bin/$f /product/lib64/$f; do
            adb_ shell test -f "$src" 2>/dev/null || continue
            mkdir -p "$OUT/bin/native$(dirname "$src")"
            adb_ pull "$src" "$OUT/bin/native$src" >/dev/null 2>&1
        done
    done
    du -sh "$OUT/bin" 2>/dev/null | tee -a "$LOG"
}

# ---------- logcat ----------

LOGCAT_PID=""

start_logcat() {
    adb_ logcat -b all -v threadtime -d > "$OUT/logcat_before_$(date +%H%M%S).txt" 2>&1
    "$ADB" logcat -b all -v threadtime < /dev/null >> "$OUT/logcat_live.txt" 2>&1 &
    LOGCAT_PID=$!
    say "logcat пишется в logcat_live.txt (pid $LOGCAT_PID)"
}

stop_logcat() {
    [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
    LOGCAT_PID=""
}
trap stop_logcat EXIT

# ---------- сценарии ----------

# label|что сделать
SCENARIOS=(
"00_acc_on|Зажигание ВКЛ, двигатель НЕ заведён, климат выключен, все стёкла закрыты."
"01_engine_start|Заведите двигатель. Подождите ~30 секунд."
"10_ac_on|Климат: включите кондиционер (A/C)."
"11_ac_off|Климат: выключите кондиционер (A/C)."
"12_temp_driver_up|Климат: температура водителя +1 шаг (запомните значение на экране)."
"13_temp_driver_up2|Климат: температура водителя ещё +1 шаг."
"14_temp_passenger_up|Климат: температура пассажира +1 шаг (если раздельный климат)."
"15_fan_up|Климат: скорость вентилятора +1."
"16_fan_up2|Климат: скорость вентилятора ещё +1."
"17_mode_face|Климат: режим обдува — на лицо."
"18_mode_feet|Климат: режим обдува — в ноги."
"19_recirc_on|Климат: включите рециркуляцию."
"20_recirc_off|Климат: выключите рециркуляцию."
"21_defrost_front|Климат: обдув лобового стекла ВКЛ."
"22_defrost_front_off|Климат: обдув лобового стекла ВЫКЛ."
"23_defrost_rear|Климат: обогрев заднего стекла ВКЛ (потом выключите на следующем шаге)."
"24_defrost_rear_off|Климат: обогрев заднего стекла ВЫКЛ."
"25_auto|Климат: режим AUTO."
"26_climate_off|Климат: выключите климат полностью."
"27_seat_heat_driver|Подогрев сиденья водителя ВКЛ (если есть)."
"28_seat_heat_driver_off|Подогрев сиденья водителя ВЫКЛ."
"29_wheel_heat|Подогрев руля ВКЛ (если есть), затем выключите на следующем шаге."
"30_wheel_heat_off|Подогрев руля ВЫКЛ."
"40_window_driver_down|Стекло водителя опустите наполовину."
"41_window_driver_up|Стекло водителя поднимите полностью."
"42_window_passenger_down|Стекло пассажира опустите наполовину."
"43_window_passenger_up|Стекло пассажира поднимите полностью."
"44_window_rear_left_down|Заднее левое стекло опустите наполовину (если есть кнопки у водителя)."
"45_window_rear_left_up|Заднее левое стекло поднимите."
"46_sunroof|Люк/шторка: приоткройте (если есть)."
"47_sunroof_close|Люк/шторка: закройте."
"50_door_driver_open|Откройте дверь водителя."
"51_door_driver_close|Закройте дверь водителя."
"52_trunk_open|Откройте багажник (если можно с места)."
"53_trunk_close|Закройте багажник."
"54_lock|Заблокируйте двери кнопкой в салоне."
"55_unlock|Разблокируйте двери."
"60_low_beam|Включите ближний свет."
"61_lights_off|Выключите свет (или AUTO, как было)."
"62_turn_left|Включите левый поворотник."
"63_turn_off|Выключите поворотник."
"64_hazard|Включите аварийку."
"65_hazard_off|Выключите аварийку."
"70_gear_r|Нога на тормозе: включите R (задний ход). Камера/парктроники могут включиться."
"71_gear_p|Верните P."
"72_brake|Нажмите и держите педаль тормоза, затем Enter."
"73_brake_off|Отпустите тормоз."
"74_parking_brake|Электроручник: отпустите (нога на тормозе!), затем снова затяните на следующем шаге."
"75_parking_brake_on|Электроручник: затяните."
"80_trip_screen|На приборке переключитесь на экран бортового компьютера (расход/пробег)."
"81_trip_reset|Сбросьте поездку на приборке (если можно) — чтобы увидеть, что обнулилось."
"82_rpm_2000|Двигатель: на P подержите ~2000 об/мин (педаль газа), затем Enter, не отпуская."
"83_rpm_idle|Отпустите газ (холостые)."
"90_engine_stop|Заглушите двигатель (зажигание оставьте ВКЛ, если возможно)."
)

run_scenarios() {
    say "=== СЦЕНАРИИ ==="
    echo "Для каждого шага: снимаем срез «до», вы делаете действие, нажимаете Enter — срез «после»."
    echo "Enter — выполнить, s — пропустить шаг, q — закончить."
    find_vhals
    start_logcat
    snapshot "000_baseline"
    local item label text ans
    for item in "${SCENARIOS[@]}"; do
        label=${item%%|*}
        text=${item#*|}
        echo
        echo ">>> [$label] $text"
        read -r -p "    Готово? (Enter / s / q): " ans || ans=q
        case "$ans" in
            q|Q) break ;;
            s|S) marker "SKIP $label"; continue ;;
        esac
        marker "ACTION $label: $text"
        # Даём шине обновиться.
        sleep 2
        snapshot "$label"
        say "срез $label готов"
    done
    stop_logcat
}

# Периодические срезы: прогрев двигателя, поездка.
run_watch() {
    local minutes=${1:-10} every=${2:-15}
    say "=== WATCH: $minutes мин, каждые $every с ==="
    find_vhals
    start_logcat
    local end=$(( $(date +%s) + minutes * 60 )) n=0
    while [ "$(date +%s)" -lt "$end" ]; do
        n=$((n + 1))
        snapshot "watch_$(date +%H%M%S)_$(printf %03d $n)"
        sleep "$every"
    done
    stop_logcat
}

# ---------- запуск ----------

check_device
say "вывод: $OUT"
case "$MODE" in
    static) static_info ;;
    scenarios) run_scenarios ;;
    watch) run_watch "${2:-10}" "${3:-15}" ;;
    all) static_info; run_scenarios ;;
    *) echo "режим: static | scenarios | watch [мин] [с] | all" >&2; exit 2 ;;
esac
say "готово: $OUT ($(du -sh "$OUT" | cut -f1))"
