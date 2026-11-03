# 接回 dsh-go 时要改的文件

源码目录按 dsh-go 路径镜像；以下文件是 dsh-go 主工程里必须同步的接入点。

## Android

- `app/android/app/src/main/AndroidManifest.xml`
  - 增加 `READ_CALENDAR`、`WRITE_CALENDAR`
- `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/MainActivity.kt`
  - 注册 `CalendarPlugin`
- `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/SystemApi.kt`
  - `Args.raw`：让 `CalendarCore` 严格读取计划文件里的数组
- `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/GuestSetup.kt`
  - `writeAgentsMd()` 增加 `calendarControl`
  - 开启时追加 `CalendarGuest.AGENTS_SECTION`
- `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/DshProcess.kt`
  - 增加 `calendarMounted`
  - `start()` 增加 `calendar` 参数
  - 依次叠加 phone、calendar、search、auth、speech overlay
- `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeController.kt`
  - 启动前 `CalendarControl.prepareLaunch()`
  - ready / stopped / 启动失败时同步 calendar bridge
  - 把 calendar 状态传给 `GuestSetup.writeAgentsMd()`

## Flutter 与设置

- `app/lib/ui/settings/index/spots.dart`
- `app/lib/ui/settings/index/settings_pages.dart`
- `app/lib/ui/settings/settings_screen.dart`
- `app/lib/l10n/parts/calendar_zh.arb`
- `app/lib/l10n/parts/calendar_en.arb`
- 运行 `python3 app/tool/l10n_merge.py`

## 文档

- 根 `README.md`、`README.en.md`
- `docs/guide/zh/10-settings.md`、`docs/guide/en/10-settings.md`
- 运行 `python3 app/tool/guide_sync.py`

## 验证

```bash
JAVA_HOME=/home/cyn/dev/jdk-17 PATH=/home/cyn/dev/jdk-17/bin:$PATH \
  ./gradlew :app:testArm64DebugUnitTest

cd app
PATH=/home/cyn/dev/flutter/bin:$PATH flutter analyze
python3 app/tool/l10n_merge.py --check
python3 app/tool/guide_sync.py --check
```

真机还要验证权限拒绝、选择日历、同 key 更新、只删 DSH 事件、提醒、关闭开关后重启、安全模式不挂载。
