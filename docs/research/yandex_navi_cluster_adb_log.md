# Исследование: уведомления Яндекс Навигатора → приборка (через adb)

Начато 2026-10-25. ГУ: 192.168.111.104 (adb по сети). Код не пишем — только исследование.
Формат: каждое действие — команда, краткий результат, вывод.

## 2026-10-25 15:36:41 Подключение
```
$ adb connect 192.168.111.104
* daemon not running; starting now at tcp:5037
* daemon started successfully
connected to 192.168.111.104:5555
List of devices attached
192.168.111.104:5555   device product:g6sh_t18fl3_international model:G6SH_r8a7795 device:g6sh transport_id:1

```

### 15:36:52 Идентификация ГУ
```
$ adb shell id; getprop ro.build.fingerprint; getprop ro.build.version.release; pm list packages | grep -iE "yandex|navi|map|cluster|desay|dash|turbodog|amap|autonavi" ; dumpsys display | grep -E "mDisplayId|DisplayDeviceInfo" | head
uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input),1007(log),1011(adb),1015(sdcard_rw),1028(sdcard_r),3001(net_bt_admin),3002(net_bt),3003(inet),3006(net_bw_stats),3009(readproc),3011(uhid) context=u:r:shell:s0
Renesas/g6sh_t18fl3_international/g6sh:9/PQ2A.190405.003/178:user/dev-keys
9
package:com.desaysv.carlan
package:com.desaysv.bluetooth.phone
package:com.desaysv.logmanager
package:com.desaysv.vrcontrolmgr
package:com.desaysv.svhvac
package:com.desaysv.vehicle.carplayapp
package:com.desaysv.update
package:com.desaysv.mediaprovider.overlay2
package:com.desaysv.mediaprovider.overlay3
package:com.desaysv.usbvideoapp
package:com.desaysv.service.link
package:com.desaysv.mediaprovider
package:com.desaysv.mediaservice
package:com.desaysv.assistant
package:com.desaysv.mediaapp.overlay2
package:com.desaysv.mediaapp.overlay3
package:com.desaysv.vehiclesetting.overlay2
package:com.desaysv.vehiclesetting.overlay3
package:com.desaysv.launcher
package:com.desaysv.setting.overlay2
package:com.desaysv.setting.overlay3
package:com.desaysv.svhvac.overlay2
package:com.desaysv.svhvac.overlay3
package:com.desaysv.bluetooth.phone.overlay2
package:com.desaysv.bluetooth.phone.overlay3
package:com.desaysv.service.bluetooth
package:com.desaysv.electronicmanual
package:com.desaysv.mediaapp
package:com.desaysv.svcarinfo
package:com.desaysv.upgrade
package:ru.yandex.yandexnavi
package:com.desaysv.upgrade.overlay2
package:com.desaysv.upgrade.overlay3
package:com.desaysv.usbpicture
package:com.desaysv.tboxinfo
package:com.desaysv.meterinteraction
package:com.desaysv.usbpicture.overlay2
package:com.desaysv.usbpicture.overlay3
package:com.desaysv.usbvideoapp.overlay2
package:com.desaysv.usbvideoapp.overlay3
package:com.desaysv.tboxservice
package:com.desaysv.vehiclesetting
package:com.desaysv.ivi.adapterservice
package:com.desaysv.mapservice
package:com.tenet.clusterbridge
package:com.desaysv.vehicle.externallink
package:com.desaysv.vrservice
package:com.desaysv.electronicmanual.overlay2
package:com.desaysv.electronicmanual.overlay3
package:com.dash.headunit
package:com.desaysv.ftpserver
package:com.desaysv.setting
  DisplayDeviceInfo{"Built-in Screen": uniqueId="local:0", 1920 x 720, modeId 1, defaultModeId 1, supportedModes [{id=1, width=1920, height=720, fps=55.0}], colorMode 0, supportedColorModes [], HdrCapabilities android.view.Display$HdrCapabilities@40f16308, density 160, 243.84 x 182.88 dpi, appVsyncOff 1000000, presDeadline 18181818, touch INTERNAL, rotation 0, type BUILT_IN, state ON, FLAG_DEFAULT_DISPLAY, FLAG_ROTATES_WITH_CONTENT, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS}
  DisplayDeviceInfo{"Экран HDMI": uniqueId="local:1", 1920 x 720, modeId 2, defaultModeId 2, supportedModes [{id=2, width=1920, height=720, fps=55.0}], colorMode 0, supportedColorModes [], HdrCapabilities android.view.Display$HdrCapabilities@40f16308, density 213, 213.0 x 213.0 dpi, appVsyncOff 1000000, presDeadline 18181818, touch EXTERNAL, rotation 0, type HDMI, state ON, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION}
    mDisplayId=0
    mDisplayId=1
```

### 15:37:00 Пакеты-кандидаты: пути, версии
```
$ adb shell for p in com.tenet.clusterbridge com.desaysv.meterinteraction com.desaysv.mapservice ru.yandex.yandexnavi com.desaysv.carlan; do echo "== $p"; pm path $p; dumpsys package $p | grep -E "versionName|versionCode|sharedUser|flags=|firstInstall|lastUpdate" | head -6; done
== com.tenet.clusterbridge
package:/data/app/com.tenet.clusterbridge-fsRLqHKYTnsfcd2UNKG00g==/base.apk
    versionCode=3 minSdk=22 targetSdk=34
    versionName=0.1.2
    flags=[ DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA ]
    firstInstallTime=2026-10-09 16:04:07
    lastUpdateTime=2026-10-09 16:04:07
== com.desaysv.meterinteraction
package:/product/app/SVMeterInteraction/SVMeterInteraction.apk
    sharedUser=SharedUserSetting{cfa6ead android.uid.system/1000}
    versionCode=1 minSdk=27 targetSdk=28
    versionName=T1D-PHEV_SVMeterInteraction_02_31.01_251226_
    flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    firstInstallTime=2025-12-26 11:16:35
    lastUpdateTime=2025-12-26 08:11:41
== com.desaysv.mapservice
package:/product/app/SVMapService/SVMapService.apk
    sharedUser=SharedUserSetting{cfa6ead android.uid.system/1000}
    versionCode=1 minSdk=28 targetSdk=28
    versionName=T1D_MapService_02_12._251226_
    flags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
    firstInstallTime=2025-12-26 11:16:36
    lastUpdateTime=2025-12-26 08:11:41
== ru.yandex.yandexnavi
package:/data/app/ru.yandex.yandexnavi-P_9Ws8Bf-talNdeZIy557g==/base.apk
    versionCode=736092921 minSdk=21 targetSdk=31
    versionName=14.8.4
    flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA LARGE_HEAP ]
    firstInstallTime=2026-10-09 16:00:22
    lastUpdateTime=2026-10-09 16:00:22
== com.desaysv.carlan
package:/system/priv-app/CarLanService/CarLanService.apk
    sharedUser=SharedUserSetting{cfa6ead android.uid.system/1000}
    versionCode=28 minSdk=28 targetSdk=28
    versionName=9
    flags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA ]
    firstInstallTime=2025-12-26 11:26:35
    lastUpdateTime=2025-12-26 08:18:08
```

### 15:37:14 Вытянуты APK
`adb pull` clusterbridge base.apk и SVMeterInteraction.apk в scratchpad для разбора.

### Разбор com.tenet.clusterbridge 0.1.2 (dexdump, build-tools 36.1.0)
Сторонний DEBUGGABLE APK, ставился вместе с Яндексом 2026-10-09. Классы: ClusterDisplayManager, NavigationSwitcher,
NavigationProtocol, VehicleBroadcastReceiver, ClusterBlankActivity, MediaListenerService (только медиа-диагностика).
Механика:
- `VehicleBroadcastReceiver` (exported) слушает `desay.broadcast.map.meter.interaction`, extra String `map_area`
  (или `map_status`); если в нём `RequestNaviAreaDisplay=1` / `"RequestNaviAreaDisplay":1|"1"` → навигатор на приборку,
  `=0` → обратно на ГУ.
- `switchNavigatorDisplay(on)`: на display 1 (on) — запоминает top-пакет ГУ, на ГУ кладёт ClusterBlank (off) или HOME (on),
  через 300 мс `startActivity(launchIntent ru.yandex.yandexnavi, NEW_TASK, setLaunchDisplayId(1|0))`, затем возвращает
  прежний top-пакет на display 0.
- `NavigationProtocol.notifySystemAboutNavState(on)`: `turbodog.navigation.system.message CODE=207 DATA=0`, затем
  `AUTONAVI_STANDARD_BROADCAST_SEND KEY_TYPE=10019 EXTRA_STATE=2 (+400 мс) 1` при on, `EXTRA_STATE=2` при off.
- Карлана нет («CARLAN: NOT IMPLEMENTED»).
Вывод: мост переносит ВЕСЬ Яндекс на дисплей 1 (HDMI 1920×720), т.е. картинка навигатора, а не TBT-данные.
Триггер — запрос приборки «показать область навигации» от SVMeterInteraction.

### 15:38:24 Текущее состояние: задачи по дисплеям, процессы
```
$ adb shell dumpsys activity activities | grep -E "Display #|Stack #|\* TaskRecord|realActivity" | head -40; echo ---; ps -A | grep -iE "yandex|clusterbridge|dash|mapservice|meterinter|carlan"
Display #0 (activities from top to bottom):
  Stack #1: type=standard mode=fullscreen
    * TaskRecord{e213fb7 #1562 A=com.dash.headunit U=0 StackId=1 sz=1}
      realActivity=com.dash.headunit/.MainActivity
          realActivity=com.dash.headunit/.MainActivity
  Stack #2: type=standard mode=fullscreen
    * TaskRecord{41c1324 #1563 A=ru.yandex.yandexnavi U=0 StackId=2 sz=1}
      realActivity=ru.yandex.yandexnavi/.core.NaviActivity
          realActivity=ru.yandex.yandexnavi/.core.NaviActivity
  Stack #0: type=home mode=fullscreen
    * TaskRecord{fa14e8d #1561 A=com.desaysv.launcher U=0 StackId=0 sz=1}
      realActivity=com.desaysv.launcher/com.desaysv.appmenu.activity.AppMenuActivity
          realActivity=com.desaysv.launcher/com.desaysv.appmenu.activity.AppMenuActivity
Display #1 (activities from top to bottom):
---
system        2181  1540 3724380 101416 0                   0 S com.desaysv.carlan
system        2493  1540 3734760  99872 0                   0 S com.desaysv.mapservice
system        2628  1540 3738892 106272 0                   0 S com.desaysv.meterinteraction
u0_a49        2844  1540 3797708 151520 0                   0 S com.dash.headunit
u0_a43        3629  1687 2823608 680396 0                   0 S ru.yandex.yandexnavi
u0_a43        3721  1687 1829188 130524 0                   0 S ru.yandex.yandexnavi:Metrica
u0_a43        3893  1687 1781240 122772 0                   0 S ru.yandex.yandexnavi:passport
u0_a43        3925  1687 1769260  92904 0                   0 S ru.yandex.yandexnavi:MusicSDK
```

### 15:38:39 Уведомления Яндекса (dumpsys notification --noredact)
```
$ adb shell dumpsys notification --noredact | grep -n -A60 "pkg=ru.yandex.yandexnavi" | head -150

```

### 15:38:45 Список уведомлений (кратко)
```
$ adb shell dumpsys notification --noredact | grep -E "NotificationRecord|pkg=|channel=" | head -30; echo; dumpsys notification | grep -iE "listener|enabled" | head -20
    NotificationRecord(0x0447f384: pkg=com.iflytek.autofly.sceneengine user=UserHandle{0} id=2020 tag=null importance=3 key=0|com.iflytek.autofly.sceneengine|2020|null|1000appImportanceLocked=false: Notification(channel=channel_VpaDataService pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0x00000000 category=service vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.iflytek.autofly.sceneengine id=0x7f0b0004) / com.iflytek.autofly.sceneengine:mipmap/ic_launcher
    NotificationRecord(0x0f645fa2: pkg=com.desaysv.ftpserver user=UserHandle{0} id=1 tag=null importance=3 key=0|com.desaysv.ftpserver|1|null|1000appImportanceLocked=false: Notification(channel=Desaysv_FtpService pri=0 contentView=null vibrate=null sound=null tick defaults=0x0 flags=0x62 color=0x00000000 vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.ftpserver id=0x7f020000) / com.desaysv.ftpserver:drawable/ic_launcher
    NotificationRecord(0x0150bd69: pkg=android user=UserHandle{-1} id=19 tag=null importance=3 key=-1|android|19|null|1000appImportanceLocked=false: Notification(channel=PHYSICAL_KEYBOARD pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x0 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=android id=0x010804d5) / android:drawable/ic_settings_language
    NotificationRecord(0x0c782f1c: pkg=com.desaysv.vehicle.externallink user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.vehicle.externallink|1|null|1000appImportanceLocked=false: Notification(channel=External_Link_Service_Channel_Id pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.vehicle.externallink id=0x7f020000) / com.desaysv.vehicle.externallink:drawable/ic_launcher
    NotificationRecord(0x0b4206ab: pkg=com.android.engmode user=UserHandle{0} id=1 tag=null importance=2 key=0|com.android.engmode|1|null|1000appImportanceLocked=false: Notification(channel=Act_Jump_Service_Channel_Id pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.android.engmode id=0x7f0c0000) / com.android.engmode:mipmap/ic_launcher
    NotificationRecord(0x0b1772c6: pkg=com.desaysv.svhvac user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.svhvac|1|null|1000appImportanceLocked=false: Notification(channel=HvacService pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.svhvac id=0x7f0b02a0) / com.desaysv.svhvac:mipmap/ic_launcher
    NotificationRecord(0x0987f552: pkg=com.desaysv.vrcontrolmgr user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.vrcontrolmgr|1|null|1000appImportanceLocked=false: Notification(channel=VrControlService pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.vrcontrolmgr id=0x7f0a0000) / com.desaysv.vrcontrolmgr:mipmap/ic_launcher
    NotificationRecord(0x070c149e: pkg=com.google.android.projection.sink user=UserHandle{0} id=2822 tag=null importance=2 key=0|com.google.android.projection.sink|2822|null|1000appImportanceLocked=false: Notification(channel=AndroidAuto_Service_Channel_Id pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.google.android.projection.sink id=0x7f070080) / com.google.android.projection.sink:drawable/ic_launcher
    NotificationRecord(0x02d8c395: pkg=com.desaysv.logmanager user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.logmanager|1|null|1000appImportanceLocked=false: Notification(channel= pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.logmanager id=0x7f030000) / com.desaysv.logmanager:mipmap/ic_launcher
    NotificationRecord(0x09a8dc38: pkg=com.desaysv.mediaservice user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.mediaservice|1|null|1000appImportanceLocked=false: Notification(channel=com.desaysv.mediaservice pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.mediaservice id=0x7f0a0000) / com.desaysv.mediaservice:mipmap/ic_launcher
    NotificationRecord(0x092b83e4: pkg=com.desaysv.mediaapp user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.mediaapp|1|null|1000appImportanceLocked=false: Notification(channel=com.desaysv.mediaapp pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.mediaapp id=0x7f0b0063) / com.desaysv.mediaapp.overlay3:mipmap/ic_launcher
    NotificationRecord(0x080efa50: pkg=com.desaysv.launcher user=UserHandle{0} id=2 tag=null importance=2 key=0|com.desaysv.launcher|2|null|1000appImportanceLocked=false: Notification(channel=MenuUpdateService pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.launcher id=0x7f0a0059) / com.desaysv.launcher:mipmap/ic_launcher
    NotificationRecord(0x027de26f: pkg=com.desaysv.service.link user=UserHandle{0} id=2732 tag=null importance=2 key=0|com.desaysv.service.link|2732|null|10024appImportanceLocked=false: Notification(channel=PhoneLink_Service_Test_Channel_Id pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.service.link id=0x7f0d0000) / com.desaysv.service.link:mipmap/ic_launcher
    NotificationRecord(0x0c81c45a: pkg=com.desaysv.vehicle.carplayapp user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.vehicle.carplayapp|1|null|1000appImportanceLocked=false: Notification(channel=Carplay_Service_Channel_Id pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.vehicle.carplayapp id=0x7f020003) / com.desaysv.vehicle.carplayapp:drawable/ic_launcher
    NotificationRecord(0x0ae81381: pkg=com.desaysv.meterinteraction user=UserHandle{0} id=1 tag=null importance=2 key=0|com.desaysv.meterinteraction|1|null|1000appImportanceLocked=false: Notification(channel=MeterService pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0xff607d8b vis=PRIVATE))
      icon=Icon(typ=RESOURCE pkg=com.desaysv.meterinteraction id=0x7f0a0000) / com.desaysv.meterinteraction:mipmap/ic_launcher

      effectiveNotificationChannel=NotificationChannel{mId='channel_VpaDataService', mName=VpaDataService, mDescription=, mImportance=3, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='Desaysv_FtpService', mName=FTPServer, mDescription=, mImportance=3, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='PHYSICAL_KEYBOARD', mName=Физическая клавиатура, mDescription=, mImportance=3, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=false, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=true}
      effectiveNotificationChannel=NotificationChannel{mId='External_Link_Service_Channel_Id', mName=ExternalLink, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='Act_Jump_Service_Channel_Id', mName=ActJumpService, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='HvacService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=[0], mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='VrControlService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=[0], mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='AndroidAuto_Service_Channel_Id', mName=Android Auto, mDescription=hasDescription , mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=false, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='com.desaysv.mediaservice', mName=AIDLAudioFocusService, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=[0], mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='com.desaysv.mediaapp', mName=AIDLAudioFocusService, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=[0], mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='MenuUpdateService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='PhoneLink_Service_Test_Channel_Id', mName=phoneLinkTest, mDescription=hasDescription , mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='Carplay_Service_Channel_Id', mName=Carplay, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='MeterService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=[0], mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='ScreensaverService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='SvVrAdapter', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='com.desaysv.service.bluetooth', mName=BootResumeSourceService, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=[0], mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=true, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='LocalService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      effectiveNotificationChannel=NotificationChannel{mId='PhoneCallService', mName=消息通知, mDescription=, mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=content://settings/system/notification_sound, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='null', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
```

### Уточнение задачи от пользователя
Показывать на приборке уведомления Яндекс Навигатора при движении по маршруту (манёвры), а также уведомления о
скорости (ограничение, превышение, камеры).

### Манифест Яндекса (dumpsys package ru.yandex.yandexnavi), v14.8.4
- `.projected.platformkit.presentation.service.NavigationCarAppService` — androidx.car.app.CarAppService, категория
  NAVIGATION (Android Auto / AAOS templates). Запрашивает androidx.car.app.ACCESS_SURFACE.
- `com.yandex.navi.remote.route.RemoteRouteControlBroadcastReceiver` — `yandex.auto.navigation.native.action.STOP`.
- `yandex.auto.hmi.HMIFeatureAvailabilityContentProvider` (authority `ru.yandex.yandexnavi.hmi.provider`),
  запрашиваемые права `yandex.auto.hmi.permission.TOGGLE_NOTIFICATIONS / TOGGLE_HMI` — следы интеграции с платформой Yandex.Auto.
- URL-схема `yandexnavi:` (build_route_on_map и пр.), BACKGROUND_ACTION ресиверы.
- Уведомлений Яндекса сейчас нет (маршрут не ведётся).

### SVMeterInteraction.apk — odex'ирован (classes.dex внутри APK нет), берём vdex из /product/app/SVMeterInteraction/oat
/product/app/SVMapService:
total 568
-rw-r--r-- 1 root root 574792 2025-12-26 08:11 SVMapService.apk
drwxr-xr-x 3 root root   4096 2025-12-26 08:19 oat

/product/app/SVMapService/oat:
total 4
drwxr-xr-x 2 root root 4096 2025-12-26 08:19 arm64

/product/app/SVMapService/oat/arm64:
total 2484
-rw-r--r-- 1 root root   66544 2025-12-26 08:19 SVMapService.odex
-rw-r--r-- 1 root root 2472580 2025-12-26 08:19 SVMapService.vdex

/product/app/SVMeterInteraction:
total 516
-rw-r--r-- 1 root root 520537 2025-12-26 08:11 SVMeterInteraction.apk
drwxr-xr-x 3 root root   4096 2025-12-26 08:19 oat

/product/app/SVMeterInteraction/oat:
total 4
drwxr-xr-x 2 root root 4096 2025-12-26 08:19 arm64

/product/app/SVMeterInteraction/oat/arm64:
total 4440
-rw-r--r-- 1 root root  119800 2025-12-26 08:19 SVMeterInteraction.odex
-rw-r--r-- 1 root root 4419804 2025-12-26 08:19 SVMeterInteraction.vdex

### 15:40:14 Экспорт dex из odex на ГУ через oatdump
```
$ adb shell mkdir -p /data/local/tmp/dex && cd /data/local/tmp/dex && for p in SVMeterInteraction SVMapService; do oatdump --oat-file=/product/app/$p/oat/arm64/$p.odex --export-dex-to=/data/local/tmp/dex >/dev/null 2>/data/local/tmp/dex/$p.err; tail -2 /data/local/tmp/dex/$p.err; done; ls -la /data/local/tmp/dex
total 6380
drwxrwxrwx 2 shell shell    4096 2026-10-25 15:40 .
drwxrwx--x 3 shell shell    4096 2026-10-25 15:40 ..
-rw-rw-rw- 1 shell shell 2306568 2026-10-25 15:40 SVMapService.apk_export.dex
-rw-rw-rw- 1 shell shell       0 2026-10-25 15:40 SVMapService.err
-rw-rw-rw- 1 shell shell 4210808 2026-10-25 15:40 SVMeterInteraction.apk_export.dex
-rw-rw-rw- 1 shell shell       0 2026-10-25 15:40 SVMeterInteraction.err
```

### 15:40:37 Декомпиляция системных APK
oatdump --export-dex-to на ГУ → cdex; dexdump самой ГУ (Android 9) читает cdex → текстовые дампы SVMeterInteraction.txt / SVMapService.txt.

### Итог по SVMeterInteraction (oatdump --no-disassemble на ГУ)
Свои классы: MeterService, MeterManager, SVCarLanManager, SVMediaManager, TTSManager, утилиты BT/USB/Audio/CarPlay.
Это мост медиа/радио/BT/TTS → приборка через CarLan. Навигационных TBT нет; есть строки CarPlayRouteGuidanceInfo /
car_navigation_service (ведение CarPlay — отдельный канал). Для задачи не нужен. Пользователь: «возможно это не тот путь» —
декомпиляцию прошивки прекращаем, переходим к практическим опытам через adb.

### 15:42:33 Последняя известная позиция и настройки уведомлений/доступности
```
$ adb shell dumpsys location | grep -E "Last Known Locations|gps: Location|network: Location|fused: Location" | head -6; echo; settings get secure enabled_notification_listeners; settings get secure enabled_accessibility_services
  Last Known Locations:
    gps: Location[gps 57,672517,28,163630 hAcc=1 et=+19m8s253ms alt=47.15 vel=0.0 bear=0.0 vAcc=??? sAcc=??? bAcc=??? {Bundle[{satellites=21, maxCn0=52, meanCn0=42}]}]
  Last Known Locations Coarse Intervals:
    gps: Location[gps 57,672514,28,163636 hAcc=1 et=+10m22s240ms alt=44.9 vel=0.027777778 bear=0.0 vAcc=??? sAcc=??? bAcc=??? {Bundle[{satellites=22, maxCn0=50, meanCn0=42}]}]

com.dash.headunit/com.dash.headunit.music.MediaListenerService
com.jarvis.car/com.jarvis.car.JarvisAccessibility:com.dash.headunit/com.dash.headunit.navi.cluster.NaviAccessibilityService:com.iflytek.cutefly.speechclient.hmi/com.iflytek.autofly.access.UiAccessibilityServices
```

### 15:42:41 Построение маршрута через yandexnavi://build_route_on_map
```
$ adb shell am start -a android.intent.action.VIEW -d "yandexnavi://build_route_on_map?lat_to=57.6800&lon_to=28.1800" ru.yandex.yandexnavi
Starting: Intent { act=android.intent.action.VIEW dat=yandexnavi://build_route_on_map?lat_to=57.6800&lon_to=28.1800 pkg=ru.yandex.yandexnavi }
Warning: Activity not started, its current task has been brought to the front
```

Скриншот ГУ с ведением: `docs/research/scr_route_hu.png` (манёвр налево 1,1 км 58K-327, скорость 0, лимит 90, 23 км/23 мин/16:06).

### 15:43:07 Уведомление Яндекса во время ведения
```
$ adb shell dumpsys notification --noredact | grep -A70 "pkg=ru.yandex.yandexnavi" | head -90
    NotificationRecord(0x0dcc6351: pkg=ru.yandex.yandexnavi user=UserHandle{0} id=2 tag=null importance=2 key=0|ru.yandex.yandexnavi|2|null|10043appImportanceLocked=false: Notification(channel=foreground_notification pri=-1 contentView=null vibrate=null sound=null defaults=0x0 flags=0x16a color=0x00000000 category=navigation vis=PUBLIC))
      uid=10043 userId=0
      icon=Icon(typ=RESOURCE pkg=ru.yandex.yandexnavi id=0x7f080a1a) / ru.yandex.yandexnavi:drawable/notifications_navigator_12
      flags=0x16a
      pri=-1
      key=0|ru.yandex.yandexnavi|2|null|10043
      seen=false
      groupKey=0|ru.yandex.yandexnavi|2|null|10043
      fullscreenIntent=null
      contentIntent=PendingIntent{7ef27b6: PendingIntentRecord{9741715 ru.yandex.yandexnavi startActivity (whitelist: b89dbe9:+30s0ms)}}
      deleteIntent=null
      tickerText=null
      contentView=null
      bigContentView=null
      headsUpContentView=null
      color=0x00000000      timeout=unknown
      extras={
          android.title=String (Навигатор запущен)
          android.reduced.images=Boolean (true)
          android.subText=null
          android.showChronometer=Boolean (false)
          android.text=null
          android.progress=Integer (0)
          android.progressMax=Integer (0)
          android.appInfo=ApplicationInfo (ApplicationInfo{f135620 ru.yandex.yandexnavi})
          android.showWhen=Boolean (false)
          android.largeIcon=null
          android.infoText=null
          android.progressIndeterminate=Boolean (false)
          android.remoteInputHistory=null
          android.colorized=Boolean (true)
      }
      stats=SingleNotificationStats{posttimeElapsedMs=949934, posttimeToFirstClickMs=-1, posttimeToDismissMs=-1, airtimeCount=0, airtimeMs=0, currentAirtimeStartElapsedMs=-1, airtimeExpandedMs=0, posttimeToFirstVisibleExpansionMs=-1, currentAirtimeExpandedStartElapsedMs=-1, requestedImportance=2, naturalImportance=2, isNoisy=false}
      mContactAffinity=0.0
      mRecentlyIntrusive=false
      mPackagePriority=0
      mPackageVisibility=-1000
      mUserImportance=LOW
      mImportance=LOW
      mImportanceExplanation=Вы определяете важность этих уведомлений.
      mIsAppImportanceLocked=false
      mIntercept=false
      mHidden==false
      mGlobalSortKey=intrsv=1:grnk=0x0000:gsmry=1:nsk:rnk=0x0000
      mRankingTimeMs=1792931954458
      mCreationTimeMs=1792931954458
      mVisibleSinceMs=0
      mUpdateTimeMs=1792932165344
      mInterruptionTimeMs=1792932165344
      mSuppressedVisualEffects= 0
      mSound= null
      mVibration= null
      mAttributes= AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null
      mLight= null
      mShowBadge=true
      mColorized=true
      mIsInterruptive=false
      effectiveNotificationChannel=NotificationChannel{mId='foreground_notification', mName=Фоновое ведение, mDescription=hasDescription , mImportance=2, mBypassDnd=false, mLockscreenVisibility=-1000, mSound=null, mLights=false, mLightColor=0, mVibration=null, mUserLockedFields=0, mFgServiceShown=true, mVibrationEnabled=false, mShowBadge=true, mDeleted=false, mGroup='navigation_group', mAudioAttributes=AudioAttributes: usage=USAGE_NOTIFICATION content=CONTENT_TYPE_SONIFICATION flags=0x0 tags= bundle=null, mBlockableSystem=false}
      mAdjustments=[]
    NotificationRecord(0x0447f384: pkg=com.iflytek.autofly.sceneengine user=UserHandle{0} id=2020 tag=null importance=3 key=0|com.iflytek.autofly.sceneengine|2020|null|1000appImportanceLocked=false: Notification(channel=channel_VpaDataService pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x62 color=0x00000000 category=service vis=PRIVATE))
      uid=1000 userId=0
      icon=Icon(typ=RESOURCE pkg=com.iflytek.autofly.sceneengine id=0x7f0b0004) / com.iflytek.autofly.sceneengine:mipmap/ic_launcher
      flags=0x62
      pri=0
      key=0|com.iflytek.autofly.sceneengine|2020|null|1000
      seen=false
      groupKey=0|com.iflytek.autofly.sceneengine|2020|null|1000
      fullscreenIntent=null
      contentIntent=null
      deleteIntent=null
      tickerText=null
      contentView=null
      bigContentView=null
```

### 15:43:23 Яндекс в фон: запускаем дашборд
```
$ adb shell am start -n com.dash.headunit/.MainActivity; sleep 6; dumpsys activity activities | grep -E "mResumedActivity|mFocusedActivity" ; dumpsys notification --noredact | grep -A40 "pkg=ru.yandex.yandexnavi" | grep -E "contentView|bigContentView|android\.(title|text|subText|bigText|infoText|template|progress)|icon=|largeIcon"
Starting: Intent { cmp=com.dash.headunit/.MainActivity }
Warning: Activity not started, its current task has been brought to the front
    mResumedActivity: ActivityRecord{b779789 u0 ru.yandex.yandexnavi/.core.NavigatorActivity t1563}
    NotificationRecord(0x0e43f38b: pkg=ru.yandex.yandexnavi user=UserHandle{0} id=2 tag=null importance=2 key=0|ru.yandex.yandexnavi|2|null|10043appImportanceLocked=false: Notification(channel=foreground_notification pri=-1 contentView=null vibrate=null sound=null defaults=0x0 flags=0x16a color=0x00000000 category=navigation vis=PUBLIC))
      icon=Icon(typ=RESOURCE pkg=ru.yandex.yandexnavi id=0x7f080a1a) / ru.yandex.yandexnavi:drawable/notifications_navigator_12
      contentView=null
      bigContentView=null
          android.title=String (Навигатор запущен)
          android.subText=null
          android.text=null
          android.progress=Integer (0)
          android.progressMax=Integer (0)
          android.largeIcon=null
          android.infoText=null
          android.progressIndeterminate=Boolean (false)
```

### 15:43:49 HOME (Яндекс полностью скрыт) → уведомление
```
$ adb shell input keyevent 3; sleep 8; dumpsys activity activities | grep -E "mResumedActivity"; dumpsys notification --noredact | grep -A45 "pkg=ru.yandex.yandexnavi" | grep -E "Notification\(|contentView|bigContentView|android\.[a-zA-Z.]+=|icon="
    mResumedActivity: ActivityRecord{6aa68ef u0 com.desaysv.launcher/com.desaysv.appmenu.activity.AppMenuActivity t1561}
    NotificationRecord(0x0efdefa5: pkg=ru.yandex.yandexnavi user=UserHandle{0} id=2 tag=null importance=2 key=0|ru.yandex.yandexnavi|2|null|10043appImportanceLocked=false: Notification(channel=foreground_notification pri=2 contentView=ru.yandex.yandexnavi/0x7f0d029f vibrate=null sound=null defaults=0x0 flags=0x16a color=0xff1669fa category=navigation vis=PUBLIC))
      icon=Icon(typ=RESOURCE pkg=ru.yandex.yandexnavi id=0x7f080a1a) / ru.yandex.yandexnavi:drawable/notifications_navigator_12
      contentView=ru.yandex.yandexnavi/0x7f0d029f (0 bytes): android.widget.RemoteViews@c96a32b
      bigContentView=ru.yandex.yandexnavi/0x7f0d029d (0 bytes): android.widget.RemoteViews@1cc388
          android.title=String (1,06 км)
          android.reduced.images=Boolean (true)
          android.subText=null
          android.showChronometer=Boolean (false)
          android.text=String (58К-327)
          android.progress=Integer (0)
          android.progressMax=Integer (0)
          android.appInfo=ApplicationInfo (ApplicationInfo{f135620 ru.yandex.yandexnavi})
          android.contains.customView=Boolean (true)
          android.showWhen=Boolean (false)
          android.largeIcon=null
          android.infoText=null
          android.progressIndeterminate=Boolean (false)
          android.remoteInputHistory=null
          android.colorized=Boolean (true)
```

### 15:44:15 Отчёты прошлых тестов на ГУ
```
$ adb shell ls -la /sdcard/Android/data/com.dash.headunit/files/navi/ 2>&1 | tail -40
total 76
drwxrwx--x 2 u0_a49 sdcard_rw 4096 2026-10-25 08:13 .
drwxrwx--x 8 u0_a49 sdcard_rw 4096 2026-10-23 15:57 ..
-rw-rw---- 1 u0_a49 sdcard_rw    3 2026-10-23 15:59 n1.done
-rw-rw---- 1 u0_a49 sdcard_rw 2082 2026-10-23 15:59 n1.txt
-rw-rw---- 1 u0_a49 sdcard_rw    3 2026-10-23 16:02 n2.done
-rw-rw---- 1 u0_a49 sdcard_rw 2082 2026-10-23 16:02 n2.txt
-rw-rw---- 1 u0_a49 sdcard_rw    3 2026-10-23 16:04 n3.done
-rw-rw---- 1 u0_a49 sdcard_rw 4514 2026-10-23 16:04 n3.txt
-rw-rw---- 1 u0_a49 sdcard_rw 6805 2026-10-23 16:50 peek_20261023_165046.txt
-rw-rw---- 1 u0_a49 sdcard_rw 8213 2026-10-25 08:12 peek_20261025_081224.txt
-rw-rw---- 1 u0_a49 sdcard_rw  798 2026-10-23 21:35 sla_20261023_213526.txt
-rw-rw---- 1 u0_a49 sdcard_rw  420 2026-10-23 21:35 sla_20261023_213545.txt
-rw-rw---- 1 u0_a49 sdcard_rw  420 2026-10-23 21:36 sla_20261023_213606.txt
-rw-rw---- 1 u0_a49 sdcard_rw  420 2026-10-24 10:57 sla_20261024_105750.txt
-rw-rw---- 1 u0_a49 sdcard_rw  420 2026-10-25 08:13 sla_20261025_081300.txt
```

### 15:44:39 NAVI_CLUSTER dump при Яндексе в фоне (label bg1)
```
$ adb shell am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd dump --es label bg1; sleep 4; cat /sdcard/Android/data/com.dash.headunit/files/navi/bg1.txt
Broadcasting: Intent { act=com.dash.headunit.NAVI_CLUSTER flg=0x400000 cmp=com.dash.headunit/.diag.NaviClusterReceiver (has extras) }
Broadcast completed: result=0
# time=2026-10-25 15:44:35.401 +0300
настройка «на приборке»: true, ширина 1100
оверлей: true, спец. возможности: true (служба подключена), уведомления: true
окно на приборке: true

== дисплеи
0 "Built-in Screen" 1920x720 flags=0x3 state=2
1 "Экран HDMI" 1920x720 flags=0xb state=2
приборка: 1

== ведение сейчас
манёвр=null стрелка=256x256 до=1,06 км улица=58К-327 осталось=23 км/23 мин прибытие=16:07 огр=null камера=null/null

== последнее уведомление навигатора
key=0|ru.yandex.yandexnavi|2|null|10043 id=2 ongoing=true channel=foreground_notification
  extra android.title=1,06 км
  extra android.reduced.images=true
  extra android.showChronometer=false
  extra android.icon=2131233306
  extra android.text=58К-327
  extra android.progress=0
  extra android.progressMax=0
  extra android.contains.customView=true
  extra android.showWhen=false
  extra android.progressIndeterminate=false
  extra android.colorized=true
  [big layout=7f0d029d]
  LinearLayout
    LinearLayout
      ImageView #notificationTitleIcon img=VectorDrawable 16x16
      TextView #notificationTitle "Яндекс Навигатор"
    LinearLayout
      ImageView #nextManeuverViewTinted img=VectorDrawable 32x32
      ImageView #nextManeuverView (скрыт) img=null
      LinearLayout
        TextView #titleView "1,06 км"
        TextView #descriptionView "58К-327"
    LinearLayout #notificationEtaBlock
      TextView #remainingDistanceView "23 км"
      TextView #remainingTimeView "23 мин"
      TextView #timeOfArrivalView "16:07"
    LinearLayout #notification_actions_block
      LinearLayout #firstActionButton
        ImageView #firstActionImage img=VectorDrawable 24x24
        TextView #firstActionButtonText "Без уведомлений"
      LinearLayout #secondActionButton
        ImageView #secondActionImage img=VectorDrawable 24x24
        TextView #secondActionButtonText "Завершить маршрут"
  [content layout=7f0d029f]
  FrameLayout
    LinearLayout
      ImageView #nextManeuverViewTinted img=VectorDrawable 32x32
      ImageView #nextManeuverView (скрыт) img=null
      LinearLayout
        TextView #titleView "1,06 км"
        TextView #descriptionView "58К-327"
  [headsUp layout=7f0d029b]
  LinearLayout
    LinearLayout
      ImageView #nextManeuverViewTinted img=VectorDrawable 32x32
      ImageView #nextManeuverView (скрыт) img=null
      LinearLayout
        TextView #titleView "1,06 км"
        TextView #descriptionView "58К-327"
    LinearLayout #notificationEtaBlock
      TextView #remainingDistanceView "23 км"
      TextView #remainingTimeView "23 мин"
      TextView #timeOfArrivalView "16:07"
  => манёвр=null стрелка=256x256 до=1,06 км улица=58К-327 осталось=23 км/23 мин прибытие=16:07 огр=null камера=null/null


== окна у службы спец. возможностей
id=7 type=-1 layer=4 - pkg=com.android.systemui Rect(0, 0 - 18, 720)
id=5 type=-1 layer=3 - pkg=com.desaysv.svhvac Rect(0, 0 - 1920, 20)
id=1 type=3 layer=2 - pkg=com.android.systemui Rect(0, 0 - 288, 720)
id=2 type=3 layer=1 - pkg=com.android.systemui Rect(1824, 0 - 1920, 720)
id=4 type=1 layer=0 AppMenu pkg=com.desaysv.launcher Rect(0, 0 - 1920, 720)

== окно навигатора (спец. возможности)
окна ru.yandex.yandexnavi нет среди окон

== история
1792931024946 готов
1792931025403 окно на дисплее 1 (Экран HDMI)
1792932168068 маршрут начался — показываю на приборке
1792932168778 приборка: навигация вкл (10019)
1792932168808 от приборки desay.broadcast.map.meter.interaction: {map_status=1} -> показать навигацию
1792932168877 приборка: навигация вкл (10019)
1792932169053 от приборки desay.broadcast.map.meter.interaction: {map_status=1} -> показать навигацию
1792932169068 приборка: навигация вкл (10019)
1792932169199 от приборки desay.broadcast.map.meter.interaction: {map_status=1} -> показать навигацию
1792932169211 приборка: навигация вкл (10019)
1792932213706 маршрут закончился — возвращаю приборку
1792932213713 приборка: навигация выкл (10019)
1792932221665 маршрут начался — показываю на приборке
1792932221690 приборка: навигация вкл (10019)
```

### Вывод по опытам с уведомлением (15:42–15:45)
- Яндекс на переднем плане ИЛИ подложкой под дашбордом (стек resumed) → уведомление «Навигатор запущен», без манёвра.
- Яндекс в фоне (HOME, на экране штатный лаунчер) → уведомление `category=navigation` с custom RemoteViews:
  titleView «1,06 км», descriptionView «58К-327», nextManeuverViewTinted (VectorDrawable стрелки),
  remainingDistanceView «23 км», remainingTimeView «23 мин», timeOfArrivalView «16:07». Лимита скорости и камер НЕТ.
- Конвейер приложения (NAVI_CLUSTER dump bg1) это уже разбирает; на дисплее 1 (`screencap -d 1`) нарисовано:
  стрелка + 1,06 км + 58K-327 + «23 км · 23 мин · 16:07» → `docs/research/scr_cluster_d1_bg.png`.
- Прошлые отчёты на ГУ: sla_* — пути SOME/IP NavigationService (служба не найдена) и broadcast cluster cmd=420 (нет приёмников)
  не сработали; peek_* — служба доступности видит окно Яндекса только когда зона касаний дашборда его не перекрывает.
ПРОБЕЛЫ: (1) манёвры, когда Яндекс показан подложкой; (2) лимит скорости/камеры/превышение — в уведомлении их нет вообще.

### 15:45:31 Яндекс на весь экран → NAVI_CLUSTER dump fg1
```
$ adb shell monkey -p ru.yandex.yandexnavi -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6; dumpsys activity activities | grep mResumedActivity; am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd dump --es label fg1 >/dev/null; sleep 4; sed -n '/== ведение сейчас/,/== последнее/p' /sdcard/Android/data/com.dash.headunit/files/navi/fg1.txt; sed -n '/== окна у службы/,$p' /sdcard/Android/data/com.dash.headunit/files/navi/fg1.txt | grep -E 'speed|limit|camera|eta|maneuver|Rect|#text|окн' | head -60
    mResumedActivity: ActivityRecord{b779789 u0 ru.yandex.yandexnavi/.core.NavigatorActivity t1563}
== ведение сейчас
манёвр=null стрелка=null до=1,1 км улица=58К-327 осталось=23 км/23 мин прибытие=16:08 огр=90 камера=null/null

== последнее уведомление навигатора
== окна у службы спец. возможностей
id=7 type=-1 layer=4 - pkg=com.android.systemui Rect(0, 0 - 18, 720)
id=5 type=-1 layer=3 - pkg=com.desaysv.svhvac Rect(0, 0 - 1920, 20)
id=1 type=3 layer=2 - pkg=com.android.systemui Rect(0, 0 - 288, 720)
id=2 type=3 layer=1 - pkg=com.android.systemui Rect(1824, 0 - 1920, 720)
id=18 type=1 layer=0 Навигатор pkg=ru.yandex.yandexnavi Rect(0, 0 - 1920, 720)
== окно навигатора (спец. возможности)
                          FrameLayout #contextmaneuverview
                              ImageView #image_maneuverballoon_maneuver
                              TextView #text_maneuverballoon_distance "1,1"
                              TextView #text_maneuverballoon_metrics " км"
                              TextView #text_nextstreet "58К-327"
                          RelativeLayout #speed_group
                            FrameLayout #speedview_guidance
                              TextView #text_speed_value "0"
                            FrameLayout #speedlimitview_guidance
                              View #view_speedlimit_alarm (не виден)
                              TextView #text_speedlimit "90"
                          ViewGroup #group_progresseta
                              ViewGroup #etaview
                                TextView #textview_eta_distance "23 км"
                                TextView #textview_eta_arrival "16:08"
                                TextView #textview_eta_time "23 мин"
                    FrameLayout #zerospeed_card_container
                    FrameLayout #geo_object_card_promodetails_container
1792931025403 окно на дисплее 1 (Экран HDMI)
```

### 15:46:05 Подложка + peek 25 с → dump pk1
```
$ adb shell am start -n com.dash.headunit/.MainActivity >/dev/null; sleep 5; am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd peek --ei sec 25 >/dev/null; sleep 4; am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd dump --es label pk1 >/dev/null; sleep 4; sed -n '/== ведение сейчас/,/^$/p' /sdcard/Android/data/com.dash.headunit/files/navi/pk1.txt; grep -E 'Rect|speed|limit|camera|eta_|maneuverballoon|nextstreet' /sdcard/Android/data/com.dash.headunit/files/navi/pk1.txt | head -40
Warning: Activity not started, its current task has been brought to the front
== ведение сейчас
манёвр=null стрелка=null до=1,1 км улица=58К-327 осталось=23 км/23 мин прибытие=16:09 огр=90 камера=null/null

id=7 type=-1 layer=3 - pkg=com.android.systemui Rect(0, 0 - 18, 720)
id=5 type=-1 layer=2 - pkg=com.desaysv.svhvac Rect(0, 0 - 1920, 20)
id=8 type=1 layer=1 Tenet t7 dash pkg=com.dash.headunit Rect(0, 21 - 1920, 533)
id=19 type=1 layer=0 Навигатор pkg=ru.yandex.yandexnavi Rect(90, 20 - 950, 534)
                              ImageView #image_maneuverballoon_maneuver
                              TextView #text_maneuverballoon_distance "1,1"
                              TextView #text_maneuverballoon_metrics " км"
                              TextView #text_nextstreet "58К-327"
                          RelativeLayout #speed_group
                            FrameLayout #speedview_guidance
                              TextView #text_speed_value "0"
                            FrameLayout #speedlimitview_guidance
                              View #view_speedlimit_alarm (не виден)
                              TextView #text_speedlimit "90"
                                TextView #textview_eta_distance "23 км"
                                TextView #textview_eta_arrival "16:09"
                                TextView #textview_eta_time "23 мин"
                    FrameLayout #zerospeed_card_container
```

### Служба доступности (15:46–15:48)
- Яндекс на весь экран (fg1): видны #text_maneuverballoon_distance/#text_nextstreet, #speed_group → #text_speed_value «0»,
  #speedlimitview_guidance → #text_speedlimit «90», #view_speedlimit_alarm (скрыт — индикатор превышения), #etaview.
- Подложка под дашбордом + `NAVI_CLUSTER cmd peek` (pk1): окно Яндекса Rect(90,20-950,534), зона дашборда 21–533 →
  служба видит то же полное дерево, включая лимит 90, хотя в маленьком окне кружки не нарисованы.
- Яндекс в фоне: окна нет у службы; лимита в уведомлении нет.
- logcat Яндекса: ничего о ведении/скорости (есть MySpinProxy — поддержка Bosch mySPIN).

### 15:46:29 TBT через mapservice: 202 → 201 (налево 300 м) → лог
```
$ adb shell A=turbodog.navigation.system.message; am broadcast -a $A --ei CODE 202 --ei DATA 1 | tail -1; sleep 1; am broadcast -a $A --ei CODE 201 --ei TURN_TYPE 11 --ei TURN_DIST 300 --es NEXT_ROAD "Тестовая" --ei REMAINING_TIME 600 --ei REMAINING_DIST 5000 | tail -1; sleep 3; logcat -d | grep -iE "mapservice|carlan|turbodog|TurboDog|4100|RoadIcon|NaviStatus|SegRemain" | grep -v "ActivityManager: Sending non-protected" | tail -40
Broadcast completed: result=0
Broadcast completed: result=0
10-25 15:46:25.292  2493  2493 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.sendBroadcast:1005 android.content.ContextWrapper.sendBroadcast:444 com.desaysv.mapservice.util.ActivityUtil.sendBroadcast:188 com.desaysv.mapservice.adapter.map.turbodog.TurboDogAdapter$3.onReceive:512 android.app.LoadedApk$ReceiverDispatcher$Args.lambda$getRunnable$0:1391 
10-25 15:46:25.298  2744  2744 I VehiclePlatform: [libcarplay|server] : SetNativeNaviStatus - Begin
10-25 15:46:25.298  2744  2744 I VehiclePlatform: [libcarplay|CarPlayMgr] : SVPCarPlayMgr::SetNativeNaviStatus - m_nativeNaviStatus = 0
10-25 15:46:25.298  2744  2744 I VehiclePlatform: [libcarplay|server] : SetNativeNaviStatus - End
```

### 15:46:38 Лог mapservice/carlan после 202/201
```
$ adb shell logcat -d --pid=$(pidof com.desaysv.mapservice) | tail -30; echo ---carlan; logcat -d --pid=$(pidof com.desaysv.carlan) | tail -15
10-25 15:46:26.470  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig4:2800500302000010
10-25 15:46:26.470  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig5:0903881200000f00
10-25 15:46:26.470  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig6:0000000000000000
10-25 15:46:26.470  2493  2808 I PlatoformUtil-ConfigUtil: carConfig load success
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig1:afbc2d45
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig2:5341521a
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig3:500e4c6023042a03
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig4:2800500302000010
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig5:0903881200000f00
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig6:0000000000000000
10-25 15:46:26.474  2493  2808 I PlatoformUtil-ConfigUtil: carConfig load success
10-25 15:46:26.479  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig1:[175, 188, 45, 69]
10-25 15:46:26.479  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig2:[83, 65, 82, 26]
10-25 15:46:26.479  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig3:[80, 14, 76, 96, 35, 4, 42, 3]
10-25 15:46:26.479  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig4:[40, 0, 80, 3, 2, 0, 0, 16]
10-25 15:46:26.479  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig5:[9, 3, 136, 18, 0, 0, 15, 0]
10-25 15:46:26.479  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig6:[0, 0, 0, 0, 0, 0, 0, 0]
10-25 15:46:26.480  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig1:afbc2d45
10-25 15:46:26.480  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig2:5341521a
10-25 15:46:26.480  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig3:500e4c6023042a03
10-25 15:46:26.480  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig4:2800500302000010
10-25 15:46:26.481  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig5:0903881200000f00
10-25 15:46:26.481  2493  2808 I PlatoformUtil-ConfigUtil: [loadConfig] carConfig6:0000000000000000
10-25 15:46:26.481  2493  2808 I PlatoformUtil-ConfigUtil: carConfig load success
10-25 15:46:26.485  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig1:[175, 188, 45, 69]
10-25 15:46:26.485  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig2:[83, 65, 82, 26]
10-25 15:46:26.485  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig3:[80, 14, 76, 96, 35, 4, 42, 3]
10-25 15:46:26.485  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig4:[40, 0, 80, 3, 2, 0, 0, 16]
10-25 15:46:26.485  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig5:[9, 3, 136, 18, 0, 0, 15, 0]
10-25 15:46:26.485  2493  2808 I PlatoformUtil-ConfigT1DImpl: [getConfig] carConfig6:[0, 0, 0, 0, 0, 0, 0, 0]
---carlan
```

### 15:46:45 Лог mapservice без ConfigUtil
```
$ adb shell logcat -d --pid=$(pidof com.desaysv.mapservice) | grep -v PlatoformUtil-Config | tail -40
--------- beginning of system
10-25 15:46:25.292  2493  2493 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.sendBroadcast:1005 android.content.ContextWrapper.sendBroadcast:444 com.desaysv.mapservice.util.ActivityUtil.sendBroadcast:188 com.desaysv.mapservice.adapter.map.turbodog.TurboDogAdapter$3.onReceive:512 android.app.LoadedApk$ReceiverDispatcher$Args.lambda$getRunnable$0:1391 
--------- beginning of main
```

### 15:46:56 TBT: завершение (203)
```
$ adb shell am broadcast -a turbodog.navigation.system.message --ei CODE 203 | tail -1; sleep 1; logcat -d | grep -E "SetNativeNaviStatus|m_nativeNaviStatus" | tail -3
Broadcast completed: result=0
10-25 15:46:54.492  2744  2744 I VehiclePlatform: [libcarplay|server] : SetNativeNaviStatus - Begin
10-25 15:46:54.492  2744  2744 I VehiclePlatform: [libcarplay|CarPlayMgr] : SVPCarPlayMgr::SetNativeNaviStatus - m_nativeNaviStatus = 1
10-25 15:46:54.492  2744  2744 I VehiclePlatform: [libcarplay|server] : SetNativeNaviStatus - End
```
Вывод: 202 доходит до mapservice (TurboDogAdapter$3 → SetNativeNaviStatus в libcarplay). Пересылку 201 в CarLan по логу не подтвердить (mapservice молчит) — нужен визуальный контроль приборки.

### 15:49 Уборка
Удалён /data/local/tmp/dex на ГУ (oatdump-выгрузки). Маршрут в Яндексе оставлен построенным.

## ИТОГИ (2026-10-25)

Источники данных по режимам показа Яндекса:
| Режим Яндекса | Уведомление | Служба доступности |
|---|---|---|
| в фоне (не виден) | манёвр-стрелка, расстояние, улица, остаток, прибытие | окна нет |
| на весь экран | «Навигатор запущен» (пусто) | всё: манёвр, улица, ETA, скорость, лимит, alarm превышения |
| подложка под дашбордом | пусто | ничего — окно перекрыто зоной касаний дашборда |
| подложка + peek (зона касаний короче окна) | пусто | всё, включая лимит (хотя в маленьком окне он не нарисован) |

Скорость: лимит (#text_speedlimit), текущая (#text_speed_value), превышение (#view_speedlimit_alarm — видимость)
есть ТОЛЬКО через службу доступности; в уведомлении их нет. Камеры (#view_notification_speedcamera_*) — id из кода,
на стоянке не проверить.

Вывод на приборку: оверлей на дисплее 1 работает (screencap -d 1). Родной TBT mapservice: 202 доходит, 201 не подтверждён.

Варианты, не взятые в работу:
- com.tenet.clusterbridge-подход (весь Яндекс на дисплей 1): на приборке полный UI Яндекса со скоростью без разбора,
  но на ГУ Яндекса нет, а служба доступности Android 9 не видит окна дисплея 1.
- Стать хостом NavigationCarAppService (androidx.car.app) или Bosch mySPIN — тяжело, у CarAppService проверка хоста.

Следующие опыты (нужен человек у приборки / поездка):
1. Визуально: рисует ли приборка родной TBT на 202/201 (в каком виде приборки).
2. Поездка по маршруту: dump в момент камеры и превышения — подтвердить id камер и видимость view_speedlimit_alarm.
3. Подложка с постоянно «короткой» зоной касаний (peek насовсем): стабильно ли служба видит Яндекс.

## 2026-10-25 15:48:56 Пункт 1: визуальная проверка родного TBT приборки
Скрипт scratchpad/tbt_test.sh: 202 DATA=1, затем 201 каждые 2 с, 3 фазы по 60 с, в конце 203.
Фаза 1: TURN_TYPE=11 (налево), улица «Тестовая», дистанция 900→0 м
Фаза 2: TURN_TYPE=12 (направо), «Проверочная»; Фаза 3: TURN_TYPE=16 (кольцо), «Кольцевая»
15:48:56 202 отправлен
15:48:56 фаза TURN_TYPE=11 Тестовая
15:50:03 фаза TURN_TYPE=12 Проверочная

### Наблюдение пользователя (пункт 1)
«Вверху приборки меняется расстояние, написано "Войти" и "Тестовая". Манёвр меняется.»
→ РОДНОЙ TBT ПРИБОРКИ РАБОТАЕТ: turbodog.navigation.system.message 202/201 от любого отправителя (даже adb shell)
доходит через mapservice → CarLan до QNX-приборки. Отображается в верхней строке приборки (поверх обычного вида),
TURN_DIST и TURN_TYPE обновляются, NEXT_ROAD выводится. Надпись «Войти» — подпись манёвра (уточнить, от чего зависит).
15:51:11 фаза TURN_TYPE=16 Кольцевая

### 15:51:46 ISA: есть ли com.astrob.mapisa и попытки привязки
```
$ adb shell pm list packages | grep -i astrob; echo "pm: $?"; pm query-services -a action.astrob.mapisa.mapclient 2>&1 | head -5; logcat -d -b all | grep -E "MapClient|ClientBase|mapisa|IsaInfo" | tail -10; dumpsys activity services com.desaysv.mapservice | grep -iE "astrob|mapisa|ConnectionRecord" | head
pm: 1
No services found
```
15:52:18 203 отправлен, тест окончен
```
10-25 15:51:25.895  2493  2544 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.sendBroadcast:1005 android.content.ContextWrapper.sendBroadcast:444 com.desaysv.mapservice.util.ActivityUtil.sendTurboDogScreenBroadcast:229 com.desaysv.mapservice.manager.MeterControl.displayControl:55 com.desaysv.mapservice.manager.SVCarLanManager$1$1.onMessage:75 
10-25 15:51:30.214  2440  3149 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.bindService:1611 android.content.ContextWrapper.bindService:698 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.connectService:87 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.access$200:17 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI$2.run:77 
10-25 15:51:30.215  1992  6453 W ActivityManager: Unable to start service Intent { cmp=com.astrob.turbodog/.NaviAIDLService } U=0: not found
10-25 15:51:40.218  2440  3149 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.bindService:1611 android.content.ContextWrapper.bindService:698 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.connectService:87 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.access$200:17 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI$2.run:77 
10-25 15:51:40.224  1992  6453 W ActivityManager: Unable to start service Intent { cmp=com.astrob.turbodog/.NaviAIDLService } U=0: not found
10-25 15:51:50.214  2440  3149 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.bindService:1611 android.content.ContextWrapper.bindService:698 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.connectService:87 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.access$200:17 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI$2.run:77 
10-25 15:51:50.215  1992  2773 W ActivityManager: Unable to start service Intent { cmp=com.astrob.turbodog/.NaviAIDLService } U=0: not found
10-25 15:52:00.214  2440  3149 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.bindService:1611 android.content.ContextWrapper.bindService:698 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.connectService:87 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.access$200:17 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI$2.run:77 
10-25 15:52:00.218  1992  6869 W ActivityManager: Unable to start service Intent { cmp=com.astrob.turbodog/.NaviAIDLService } U=0: not found
10-25 15:52:10.214  2440  3149 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.bindService:1611 android.content.ContextWrapper.bindService:698 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.connectService:87 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.access$200:17 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI$2.run:77 
10-25 15:52:10.215  1992  6453 W ActivityManager: Unable to start service Intent { cmp=com.astrob.turbodog/.NaviAIDLService } U=0: not found
10-25 15:52:14.783  2493  2527 E SVCarLanManager: msgType=====8196======message ===== {"RequestNaviAreaDisplay":0}getSubtype==1
10-25 15:52:14.783  2493  2527 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.sendBroadcast:1005 android.content.ContextWrapper.sendBroadcast:444 com.desaysv.mapservice.util.ActivityUtil.sendTurboDogScreenBroadcast:229 com.desaysv.mapservice.manager.MeterControl.displayControl:48 com.desaysv.mapservice.manager.SVCarLanManager$1$1.onMessage:75 
10-25 15:52:14.784  2586  2604 E SVCarLanManager: msgType=====8196======message ===== {"RequestNaviAreaDisplay":0}getSubtype==1
10-25 15:52:18.665  2493  2493 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.sendBroadcast:1005 android.content.ContextWrapper.sendBroadcast:444 com.desaysv.mapservice.util.ActivityUtil.sendBroadcast:188 com.desaysv.mapservice.adapter.map.turbodog.TurboDogAdapter$3.onReceive:527 android.app.LoadedApk$ReceiverDispatcher$Args.lambda$getRunnable$0:1391 
10-25 15:52:18.668  2744  2744 I VehiclePlatform: [libcarplay|server] : SetNativeNaviStatus - Begin
10-25 15:52:18.668  2744  2744 I VehiclePlatform: [libcarplay|CarPlayMgr] : SVPCarPlayMgr::SetNativeNaviStatus - m_nativeNaviStatus = 1
10-25 15:52:18.668  2744  2744 I VehiclePlatform: [libcarplay|server] : SetNativeNaviStatus - End
10-25 15:52:20.214  2440  3149 W ContextImpl: Calling a method in the system process without a qualified user: android.app.ContextImpl.bindService:1611 android.content.ContextWrapper.bindService:698 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.connectService:87 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI.access$200:17 com.astrob.turbodog.navilibvr.ChangAnNaviOpenAPI$2.run:77 
10-25 15:52:20.215  1992  6869 W ActivityManager: Unable to start service Intent { cmp=com.astrob.turbodog/.NaviAIDLService } U=0: not found
```

### 15:52:27 ISA: условие isAsu() — номер детали ГУ
```
$ adb shell getprop persist.sys.car.part.number; getprop | grep -iE "part.number|isa|navi" | head
703002573AA
[dalvik.vm.isa.arm.features]: [default]
[dalvik.vm.isa.arm.variant]: [cortex-a53]
[dalvik.vm.isa.arm64.features]: [default]
[dalvik.vm.isa.arm64.variant]: [cortex-a53]
[persist.sv.force_disable_rtl]: [true]
[persist.sys.car.part.number]: [703002573AA]
[persist.sys.navi.activation.code]: [00000000000000000000000000000000]
[persist.sys.navi.openid]: [5e16cba3]
[persist.sys.navi.seed]: [aef9bf62575e48599586cb598e1b7fff]
[persist.sys.navi.uuid]: [FYOSX219098310002]
```

### ISA (лимит скорости) в SVMapService — разбор oatdump
- TurboDogAdapter содержит ISA-клиент `com.astrob.mapisa.mapclient.MapClient` (VERSION 1.1.20230526):
  bindService(Intent action `action.astrob.mapisa.mapclient`, package `com.astrob.mapisa`, BIND_AUTO_CREATE);
  IMapClient: tx1 registerIsaInfoCallback(IIsaInfoCallback), tx2 unregister; IIsaInfoCallback: tx1 updateIsaInfo(String json).
  JSON → Gson TurboDogISABean {NavSpeedLimit, NavSpeedLimitType, NavSpeedLimitUnits, NavSpeedLimitStatus, NavSpeedLimit_EV,
  NavRain/Snow/FogSpeedLimit, NavCountryCodeInfo, NavCurrRoadType, NavJunctions, NavRoundabouts(+Dist), NavRampInfo/Distance,
  NavTrafficlights, NavWinding} → CarInfoProxy ячейки 66, 67, 68, 69, … → приборка.
- НО подключение делается один раз в конструкторе и только если `ActivityUtil.isSupportISAFunc()` = isAsu() =
  `persist.sys.car.part.number == "703001322AA"`. На этой ГУ part.number = 703002573AA → клиент ISA не подключается никогда.
  Менять persist-свойство номера детали не будем (shell не может, и это рискованно). ISA через mapservice — ЗАКРЫТО.
- 201 (onGuideInfoHandle/$5) несёт только TURN_TYPE/TURN_DIST/TURN_TIME/NEXT_ROAD/CURRENT_ROAD/REMAINING_*; CameraInfoModel —
  только NaviStatus. Полей лимита/камер в broadcast-канале нет.

## 2026-10-25 15:54:58 Исследование оверлея на дисплее приборки (display 1)
Цель: понять видимую область 1920×720 в режиме «Карты» и удобство размещения информации.

### 15:54:58 Окна и параметры display 1
```
$ adb shell dumpsys window displays | sed -n "/Display: mDisplayId=1/,/Display: mDisplayId=\|^WINDOW MANAGER/p" | grep -E "mDisplayId|init=|cur=|app=|Window #|mBaseDisplayInfo|ty=|mFrame=" | head -30; echo ---; dumpsys window windows | grep -B2 -A6 "mDisplayId=1" | grep -E "Window #|mOwnerUid|mAttrs|mFrame|ty=" | head -20; python3 -c "import PIL; print(PIL.__version__)" 2>&1
  Display: mDisplayId=1
    init=1920x720 213dpi cur=1920x720 app=1920x720 rng=720x720-1920x1920
    mLastVisibility=false
  Display: mDisplayId=0
    init=1920x720 160dpi cur=1920x720 app=1920x720 rng=624x720-1824x1872
            app=true mVoiceInteraction=false
            app=true mVoiceInteraction=false
            app=true mVoiceInteraction=false
    mLastVisibility=false
---
  Window #10 Window{1db57fa u0 DashCluster}:
    mOwnerUid=10049 mShowToOwnerOnly=true package=com.dash.headunit appop=SYSTEM_ALERT_WINDOW
    mAttrs={(0,0)(fillxfill) sim={adjust=pan} ty=APPLICATION_OVERLAY
/system/bin/sh: python3: not found
```

### 15:55:27 Кто открывает image/png
```
$ adb shell pm query-activities -a android.intent.action.VIEW -d file:///sdcard/Download/cluster_grid.png -t image/png 2>&1 | grep -E "packageName|name=" | head; pm query-activities -a android.intent.action.VIEW -t image/* 2>&1 | grep -E "packageName=" | sort -u

```

### 15:55:38 Просмотрщики: usbpicture, браузеры
```
$ adb shell dumpsys package com.desaysv.usbpicture | grep -E "Activity|Action|Type|Scheme" | head -20; echo ---; pm query-activities -a android.intent.action.VIEW -d http://example.com 2>&1 | grep packageName= | sort -u; pm list packages | grep -iE "browser|chrome|webview|gallery|photo|file"
Activity Resolver Table:
  Non-Data Actions:
        387f1f com.desaysv.usbpicture/.ui.MainActivity filter 9ed5e30
          Action: "android.intent.action.MAIN"
  Non-Data Actions:
          Action: "android.intent.action.BOOT_COMPLETED"
          mPriority=500, mOrder=0, mHasPartialTypes=false
---
package:com.alphainventor.filemanager
package:com.google.android.webview
```

### 15:55:43 filemanager как просмотрщик
```
$ adb shell dumpsys package com.alphainventor.filemanager | grep -B1 -A4 "image" | grep -E "Activity|filter|Type|Scheme|Action" | head -20
          Type: "video/x-matroska"
        f056e0f com.alphainventor.filemanager/.activity.LaunchActivity filter 3d8bba4
          Action: "android.intent.action.VIEW"
          Scheme: "content"
          Type: "audio/wav"
          Type: "image/jpeg"
          Type: "image/gif"
          Type: "image/png"
          Type: "image/bmp"
          Type: "image/webp"
          Type: "image/svg+xml"
        f056e0f com.alphainventor.filemanager/.activity.LaunchActivity filter 3d8bba4
          Action: "android.intent.action.VIEW"
          Scheme: "content"
          Type: "audio/wav"
          Type: "image/jpeg"
          Type: "image/gif"
          Type: "image/png"
          Type: "image/bmp"
          Type: "image/webp"
```

### 15:56:04 Сетка на display 1: убрать оверлей, открыть просмотрщик
```
$ adb shell am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd remove | tail -1; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/cluster_grid.png | tail -1; sleep 2; ID=$(content query --uri content://media/external/images/media --projection _id --where "_display_name=\"cluster_grid.png\"" | sed -n "s/.*_id=\([0-9]*\).*/\1/p" | tail -1); echo id=$ID; am start --display 1 -a android.intent.action.VIEW -d content://media/external/images/media/$ID -t image/png -n com.alphainventor.filemanager/.activity.LaunchActivity; sleep 5; dumpsys activity activities | grep -A4 "Display #1" | head -6
Broadcast completed: result=0
Broadcast completed: result=0
id=704
Starting: Intent { act=android.intent.action.VIEW dat=content://media/external/images/media/704 typ=image/png cmp=com.alphainventor.filemanager/.activity.LaunchActivity }
Display #1 (activities from top to bottom):

  Stack #3: type=standard mode=fullscreen
  isSleeping=false
  mBounds=Rect(0, 0 - 0, 0)
```
- Калибровочная сетка `docs/research/grid.png` (полосы A–H по 240 px, сетка 80/160 px, красная рамка) → /sdcard/Download/cluster_grid.png,
  наш оверлей снят (`NAVI_CLUSTER cmd remove`), открыта в com.alphainventor.filemanager на `am start --display 1` (content://media/…/704).
  screencap -d 1: сетка 1:1, сверху/снизу полупрозрачные панели просмотрщика (~75 px) — `docs/research/scr_d1_grid.png`.
  Ждём наблюдение пользователя: что видно на приборке.
Наблюдение пользователя: «в режиме карта — маленькие приборы и по центру пусто». Картинка display 1 на приборку НЕ выводится
(ни сетка, ни, вероятно, наш оверлей) — видеопоток включается отдельной командой.

### 15:58:03 CarLan: службы, экспорт, права
```
$ adb shell dumpsys package com.desaysv.carlan | grep -E "Service|Action|permission|exported" | head -30; echo ---; service list | grep -iE "carlan|lan" ; echo ---; dumpsys activity services com.desaysv.carlan | grep -E "ServiceRecord|intent=|permission|app=|ConnectionRecord" | head -30
  Non-Data Actions:
          Action: "android.intent.action.MAIN"
  Non-Data Actions:
      TestCarLanService:
          Action: "TestCarLanService"
Service Resolver Table:
  Non-Data Actions:
        39a719b com.desaysv.carlan/.CarLanService filter 2b68e8a
          Action: "com.desaysv.carlan.ICarLan"
  Permission [desaysv.vehiclelan.permission.RVC] (41c938):
    perm=Permission{a1ed11 desaysv.vehiclelan.permission.RVC}
  Permission [desaysv.vehiclelan.permission.DISPLAY] (7534e76):
    perm=Permission{5a48c77 desaysv.vehiclelan.permission.DISPLAY}
    codePath=/system/priv-app/CarLanService
    resourcePath=/system/priv-app/CarLanService
    legacyNativeLibraryDir=/system/priv-app/CarLanService/lib
    declared permissions:
      desaysv.vehiclelan.permission.DISPLAY: prot=signature|privileged, INSTALLED
      desaysv.vehiclelan.permission.RVC: prot=signature|privileged, INSTALLED
    requested permissions:
      android.permission.CALL_PHONE
      android.permission.DEVICE_POWER
      android.permission.GRANT_RUNTIME_PERMISSIONS
      android.permission.INTERACT_ACROSS_USERS_FULL
      android.permission.MANAGE_ACTIVITY_STACKS
      android.permission.MODIFY_AUDIO_ROUTING
      android.permission.MODIFY_DAY_NIGHT_MODE
      android.permission.MODIFY_PHONE_STATE
      android.permission.READ_CALL_LOG
      android.permission.READ_PHONE_STATE
---
---
  * ServiceRecord{2596054 u0 com.desaysv.carlan/.CarLanService}
    intent={act=com.desaysv.carlan.ICarLan pkg=com.desaysv.carlan}
    app=ProcessRecord{1ec6601 2181:com.desaysv.carlan/1000}
      intent={act=com.desaysv.carlan.ICarLan pkg=com.desaysv.carlan}
          ConnectionRecord{45695d1 u0 CR com.desaysv.carlan/.CarLanService:@309cf8}
          ConnectionRecord{1d086e7 u0 CR com.desaysv.carlan/.CarLanService:@e81eca6}
          ConnectionRecord{53f7af5 u0 CR com.desaysv.carlan/.CarLanService:@56f102c}
          ConnectionRecord{2fddd34 u0 CR com.desaysv.carlan/.CarLanService:@742a807}
          ConnectionRecord{fe236b u0 CR com.desaysv.carlan/.CarLanService:@d91d8ba}
          ConnectionRecord{d85681c u0 CR com.desaysv.carlan/.CarLanService:@ce76d8f}
          ConnectionRecord{85e189a u0 CR com.desaysv.carlan/.CarLanService:@d76b945}
          ConnectionRecord{24fbd26 u0 CR com.desaysv.carlan/.CarLanService:@d052881}
          ConnectionRecord{890038b u0 CR com.desaysv.carlan/.CarLanService:@9fca55a}
          ConnectionRecord{8d71e7 u0 CR com.desaysv.carlan/.CarLanService:@dc3a3a6}
          ConnectionRecord{52544ca u0 CR com.desaysv.carlan/.CarLanService:@c4b3835}
          ConnectionRecord{b67818d u0 CR com.desaysv.carlan/.CarLanService:@51f3224}
          ConnectionRecord{d95a99 u0 CR com.desaysv.carlan/.CarLanService:@ef3d7e0}
          ConnectionRecord{192ff5c u0 CR com.desaysv.carlan/.CarLanService:@d5bd9cf}
          ConnectionRecord{7a52e4f u0 CR com.desaysv.carlan/.CarLanService:@b3c70ae}
          ConnectionRecord{d24c821 u0 CR com.desaysv.carlan/.CarLanService:@d89e688}
          ConnectionRecord{a54c297 u0 CR com.desaysv.carlan/.CarLanService:@fa80916}
          ConnectionRecord{932f7d7 u0 CR com.desaysv.carlan/.CarLanService:@374cb56}
      ConnectionRecord{45695d1 u0 CR com.desaysv.carlan/.CarLanService:@309cf8}
      ConnectionRecord{932f7d7 u0 CR com.desaysv.carlan/.CarLanService:@374cb56}
      ConnectionRecord{b67818d u0 CR com.desaysv.carlan/.CarLanService:@51f3224}
      ConnectionRecord{53f7af5 u0 CR com.desaysv.carlan/.CarLanService:@56f102c}
      ConnectionRecord{2fddd34 u0 CR com.desaysv.carlan/.CarLanService:@742a807}
      ConnectionRecord{890038b u0 CR com.desaysv.carlan/.CarLanService:@9fca55a}
      ConnectionRecord{7a52e4f u0 CR com.desaysv.carlan/.CarLanService:@b3c70ae}
      ConnectionRecord{52544ca u0 CR com.desaysv.carlan/.CarLanService:@c4b3835}
```

### Команды показа видео на приборке (SVMapService → CarLan 4100, JSON)
- 207 (если карта не открыта): setIsMapOpen(true), sendTurboDogScreenBroadcast(1),
  `{"DisplayCluster":"false","NaviFrontDeskStatus":"true","Perspective":0,"PerspectiveResult":"false","RequestDisplayNaviArea":"true"}`
- 205: setIsMapOpen(false), `{"DisplayCluster":"false","NaviFrontDeskStatus":"false",...,"RequestDisplayNaviArea":"false"}`
- SWIPE_LEFT_TWO / SWIPE_RIGHT_TWO и только если на переднем плане com.astrob.turbodog:
  `{"DisplayCluster":"true","NaviFrontDeskStatus":"true",...,"RequestDisplayNaviArea":"false"}` + `{"NaviDisplayArea":1,"NaviDisplayAreaResult":"true"}`
- Камеры/превышение: CameraInfoModel `{"CameraType":3,"NaviStatus":4,"SpeedingInfo":0,"WarningMessage":-1}` (TurboDog заполняет только NaviStatus).
### CarLanService (/system/priv-app, classes.dex в APK)
- Служба .CarLanService, action com.desaysv.carlan.ICarLan, БЕЗ android:permission, экспортирована (intent-filter).
- Проверки вызывающего — только dump (DUMP), setCarLanServiceHelper; LanCommonService.writeMessage(int, CommonMessage) — без проверок.
  → стороннее приложение может само слать JSON 4100 в приборку (DisplayCluster/NaviDisplayArea/CameraInfo). Из adb shell нельзя
  (bound service, не в servicemanager) — нужен код; отложено.

### 15:59:13 Сброс: turbodog CODE=205
```
$ adb shell am broadcast -a turbodog.navigation.system.message --ei CODE 205 | tail -1
Broadcast completed: result=0
```
Наблюдение после 205: в «Картах» надпись «нет информации о карте». (После 207 было: маленькие приборы, пустой центр.) Команды доходят; не хватает DisplayCluster:true.
- Ветка SWIPE_LEFT_TWO/RIGHT_TWO: если !LocalUtil.vtable@18() и ActivityUtil.isForeground("com.astrob.turbodog")
  (= getRunningTasks(1)[0].topActivity.packageName) → DisplayCluster:true + NaviDisplayArea:1. Из adb не подделать.
- Подтипы CarLan (sendMessageToCarLan(type, subtype, json)): статус/видео DisplayCluster… — (4100, 2);
  NaviDisplayArea — (4100, 5); CameraInfo {CameraType,NaviStatus,SpeedingInfo,WarningMessage} — (4100, 4).

### 16:01:00 Восстановление: закрыть просмотрщик, вернуть оверлей
```
$ adb shell am force-stop com.alphainventor.filemanager; sleep 1; am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd show | tail -1; sleep 2; dumpsys activity activities | grep -A3 "Display #1"; dumpsys window windows | grep -c DashCluster
Broadcast completed: result=0
Display #1 (activities from top to bottom):

  ResumedActivity: ActivityRecord{c22c3d1 u0 com.dash.headunit/.MainActivity t1562}
  mFocusedStack=ActivityStack{bab9842 stackId=1 type=standard mode=fullscreen visible=true translucent=true, 1 tasks} mLastFocusedStack=ActivityStack{bab9842 stackId=1 type=standard mode=fullscreen visible=true translucent=true, 1 tasks}
4
```
- Восстановлено: просмотрщик закрыт, оверлей DashCluster снова на display 1 (`cmd show`). Сетка осталась в /sdcard/Download/cluster_grid.png.

### Вывод по оверлею (на 2026-10-25)
Картинку display 1 приборка в «Картах» показывает только после `DisplayCluster:"true"` (CarLan 4100/2). Из adb этого не добиться:
mapservice шлёт его лишь при «турбодоге» на переднем плане. Рабочий путь — своё приложение привязывается к
com.desaysv.carlan/.CarLanService (action com.desaysv.carlan.ICarLan, прав не требует) и через менеджер "common"
пишет writeMessage(4100, CommonMessage(2, DisplayCluster:true…)) и (4100, CommonMessage(5, NaviDisplayArea:1…)).
Тем же путём можно пробовать штатные предупреждения: (4100, CommonMessage(4, {CameraType,NaviStatus,SpeedingInfo,WarningMessage})).
Требует кода (клиентские классы desaysv.carlan.* вшиты в SVMapService, в /system/framework их нет — придётся повторить AIDL-клиент у себя). Видимую область 1920×720
определить по сетке пока не удалось — повторить после включения видео.

(Задача про температуру за бортом вынесена в docs/research/outside_temp_adb_log.md)

## 2026-10-25 Реализация CarLan-клиента (C + B), на ГУ НЕ проверено
ГУ была недоступна по сети (No route to host) — протокол ICarLan взят из соседнего проекта
`~/Android/tenet t7/TiggoBridge/src/com/tiggo/bridge/CarLanClient.java` (ГУ Desay той же серии):
- ICarLan `desaysv.carlan.ICarLan`, tx 2 `getCarLanService("common")` → ICommon `desaysv.carlan.ICommon`;
- ICommon tx 3 `writeMessage(int type, CommonMessage)`: Parcel = type, 1, subtype, String json, -1977 → int.
- TiggoBridge: панель карты на приборке — только при 2+5+4 вместе; SpeedingInfo (4100/4) рисуется кольцом-лимитом.
  На Tiggo вдобавок нужен флаг ENABLE в QNX sva.cluster — есть ли он у нас, покажет опыт.
Код: `navi/cluster/CarLanClient.kt`, `navi/cluster/ClusterWarnings.kt`, хуки в `ClusterOverlay.announce`
(наш 2+5+4 уходит через 700 мс ПОСЛЕ 207, т.к. mapservice на 207 сам шлёт DisplayCluster:false).
`Guidance.overspeed` — видимость `#view_speedlimit_alarm`. По умолчанию выключено.

Опыты на ГУ (человек у приборки, вид «Карты»):
```
R="am broadcast -a com.dash.headunit.NAVI_CLUSTER -n com.dash.headunit/.diag.NaviClusterReceiver --es cmd"
adb shell $R carlan_video --ez on true        # видно ли наш оверлей (демо-лимит 60)?
adb shell $R carlan_cam --ei spd 90 --ei warn 1   # кольцо 90? перебрать cam 0..5, warn -1..3
adb shell $R carlan_video --ez on false
adb shell $R carlan_on --ez on true           # дальше — от маршрута Яндекса
adb shell $R dump --es label carlan1          # раздел «CarLan»: ответы writeMessage
```
