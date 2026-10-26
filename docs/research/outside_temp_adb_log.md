# Исследование: температура за бортом (приборка vs виджет «За бортом»)

Начато 2026-10-25. ГУ 192.168.111.104 (adb), приборка QNX через qconn 192.168.2.1:8000 (tools/cluster_pps.py).

## 2026-10-25 16:04:14 Новая задача: температура за бортом
Наблюдение: приборка 11.5 °C, виджет «За бортом» 13 °C. Виджет: Desay 16,19 raw−120 (шаг 1 °C), VHAL ENV_OUTSIDE_TEMPERATURE — запасной. У приборки шаг 0.5 °C.

### 16:04:17 Срез шины Desay out1 + VHAL
```
$ adb shell am broadcast -a com.dash.headunit.CAR_SNAPSHOT -n com.dash.headunit/.diag.CarSnapshotReceiver --es label out1 >/dev/null; for i in $(seq 1 40); do [ -f /sdcard/Android/data/com.dash.headunit/files/snapshots/out1.done ] && break; sleep 1; done; ls -la /sdcard/Android/data/com.dash.headunit/files/snapshots/out1.*; grep -E '^16,19|^16 19|16,19' /sdcard/Android/data/com.dash.headunit/files/snapshots/out1.txt | head -3; dumpsys car_service 2>&1 | grep -iE 'outside|0x11600703|291505923' | head -5
-rw-rw---- 1 u0_a49 sdcard_rw     3 2026-10-25 16:04 /sdcard/Android/data/com.dash.headunit/files/snapshots/out1.done
-rw-rw---- 1 u0_a49 sdcard_rw 76604 2026-10-25 16:04 /sdcard/Android/data/com.dash.headunit/files/snapshots/out1.txt
16,19: 133
16,190: 0
16,191: 0
    CarPropertyConfig{mPropertyId=291505923, mAccess=1, mAreaType=0, mChangeMode=1, mConfigArray=[], mConfigString=, mMaxSampleRate=2.0, mMinSampleRate=1.0, mSupportedAreas={}, mType=class java.lang.Float}
Property:0x11600703,Property name:ENV_OUTSIDE_TEMPERATURE,access:0x1,changeMode:0x1,config:0x[],fs min:1.0,fs max:2.0
Prop: 0x11600703, service: com.android.car.hal.PropertyHalService@42abec2
```
- 16:04 срез Desay out1: 16,19 = 133 (→13 °C по raw−120). Прямой ячейки со значением 11.5 в типичных масштабах на шине Desay нет.
- 16:04 PPS приборки (tools/cluster_pps.py explore → logs/cluster_explore_20261025_160441.txt), кандидаты под 11.5:
  /var/pps/spi_service/7902 = [11,1,103,248] (b2=103 → ×0.5−40 = 11.5); 7926 = [115,0] (LE16 ×0.1 = 11.5);
  a006 b3 = 133 (совпадает с Desay 16,19).

### 16:05–16:08 Поллинг кандидатов 3 мин (cluster_pps.py poll 3 5 --objects 7902,7926,a006)
Лог: logs/cluster_poll_20261025_160507.txt. Значения не менялись: 7902=[11,1,103,248], 7926=[115,0], a006 b3=133.
(Инструмент в diff-строках не подписывает объект — «?», сравнивает разные объекты между собой; это шум, не изменения.)
Различить кандидатов без изменения температуры нельзя.
- 16:10 Помощник логирования scratchpad/t.sh теперь пишет в outside_temp_adb_log.md.

### 16:09:06 Внешняя точка: Open-Meteo (57.67, 28.16)
```
```
Разбор 7902=[11,1,103,248]: b0=11 (целые), b1=1 (×0.5), b2=103 (×0.5−40) — трижды 11.5; 7926=[115,0] = 11.5×10. Это значение приборки.

- 16:09:47 Open-Meteo с ПК недоступен (curl: нет сети, код 000). Пробуем кэш погоды приложения на ГУ.

### 16:09:48 Кэш погоды приложения (run-as)
```
$ adb shell run-as com.dash.headunit ls shared_prefs files 2>&1 | head -20
files:
adb_key.der
adb_key_pub.der
mapkit
profileInstalled
profileinstaller_profileWrittenFor_lastUpdateTime.dat
runtime

shared_prefs:
app_settings.xml
autostart.xml
coolant_calibration.xml
dashboard_prefs.xml
elm_settings.xml
learned_actions.xml
navi_underlay.xml
shade_prefs.xml
theme_prefs.xml
trip_prefs.xml
weather_prefs.xml
```

### 16:10:19 Кэш погоды приложения (run-as com.dash.headunit cat shared_prefs/weather_prefs.xml)
```
source owm city Палкино fetchedAt 2026-10-25 15:54:00
пример часа: {'label': '18:00', 'temp': 11.91, 'code': 3}
[('18:00', 11.91), ('21:00', 12.13), ('00:00', 12.5), ('03:00', 12.24)]
days0 {'d': '2026-10-25', 'max': 12.21, 'min': 11.78, 'code': 3}
```
OWM (Палкино, получено 15:54): ближайший час 18:00 → 11.9 °C. Ближе к приборке (11.5), чем к 16,19 (13).

### 16:10:32 VHAL ENV_OUTSIDE_TEMPERATURE: значение
```
$ adb shell dumpsys car_service --hal 2>&1 | grep -iE "0x11600703|OUTSIDE" | head -5; dumpsys android.hardware.automotive.vehicle@2.0::IVehicle/default 2>&1 | head -3; lshal 2>/dev/null | grep -i vehicle | head -3
Can't find service: android.hardware.automotive.vehicle@2.0::IVehicle/default
  android.hardware.automotive.vehicle@2.0::IVehicle/default                 N/A        N/A    
  com.desaysv.vehiclelan.proxy@1.0::ICommonProxy/default                    N/A        N/A    
  com.desaysv.vehiclelan.proxy@1.0::IRvc/default                            N/A        N/A    
```
- VHAL ENV_OUTSIDE_TEMPERATURE: из shell значение не прочитать (dumpsys car_service даёт только конфиг, lshal — без dump).
  В CarHub VHAL идёт вторым после Desay, поэтому на виджет сейчас не влияет.

### Вывод (16:20)
- Виджет: Desay 16,19 = 133 → 13 °C. Группа 16 — блок климата (ClimateHub), шаг 1 °C.
- Приборка: PPS /var/pps/spi_service/7902 = [11,1,103,248] и 7926 = [115,0] — трижды кодируют 11.5 °C
  (b0 целые + b1×0.5; b2×0.5−40; LE16×0.1), шаг 0.5 °C. Это источник цифры на приборке (данные MCU по SPI).
- OWM-прогноз сегодня 11.8–12.2 °C — ближе к приборке.
- Гипотеза: разные источники — приборка берёт кузовной сигнал наружной температуры (обычно с фильтром от нагрева на стоянке),
  климат 16,19 — свой (или нефильтрованный) датчик; расхождение 1.5 °C.
- Не подтверждено: какой из байтов 7902/7926 «главный» — нужна динамика (поездка/смена температуры), поллинг 3 мин изменений не дал.

### Выхлоп (16:45): виджет «За бортом» переделан на приборку
- app/src/main/java/com/dash/headunit/car/ClusterPps.kt: в опрос добавлены объекты 7902/7926;
  ClusterParser: 7902 b2×0.5−40 — главный, 7926 LE16×0.1 — запасной (как a000/a5a5),
  фильтры −40..+60 °C и «нет данных» 0xFFFF; аксессор ClusterPps.outsideTempC().
- app/src/main/java/com/dash/headunit/car/CarHub.kt: в readMerged забортная теперь
  ClusterPps.outsideTempC() ?: merged (как ОЖ/скорость — приборка первой).
- app/src/main/java/com/dash/headunit/widgets/OutsideWidgetView.kt: половинные градусы
  показываются с одним знаком (+11,5°), целые — без дробной части.
- Тесты: ClusterParserTest — разбор 7902/7926, приоритет 7902 над 7926, отсечение мусора.
- Пока не подтверждена динамика, «главный» байт выбран по здравому смыслу (7902 b2 —
  полный диапазон −40..+87.5), при срезах в движении — перепроверить, что цифры совпадают.
