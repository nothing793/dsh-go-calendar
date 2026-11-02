# dsh-go-calendar

dsh-go 的「手机日历」内置 MCP 的源码副本：给本机插件三个固定工具 `mcp__calendar__list`、`mcp__calendar__upsert`、`mcp__calendar__remove`，把日程写进 Android 系统日历。

dsh-go 目前不接受外部 PR，这份代码先单独成仓，方便审查、跑测试和写发布说明。等 dsh-go 开放外部 PR，整棵目录迁回 dsh-go，接入点列在 [INTEGRATION.md](INTEGRATION.md)。

代码出自 [dsh-go](https://github.com/ccch1mneyyy/dsh-go)，沿用它的 AGPL-3.0，全文见 [LICENSE](LICENSE)。

## 边界

- 只在本机引擎、正常模式、用户打开「设置 › 日历」并授予 `READ_CALENDAR` / `WRITE_CALENDAR` 时挂载。
- 和「手机操控」分开：不要无障碍，不读屏幕，开关也独立。
- 只操作带 App 包名和 `dsh-calendar://event/<key>` 的事件，不列举、不修改用户自己建的事件。
- 不做双向同步，系统日历里改过的事件不会读回来。

## 目录

| 路径 | 内容 |
|---|---|
| `app/android/.../calendar/` | Kotlin 日历 MCP、Calendar Provider 适配、Flutter MethodChannel |
| `app/android/.../test/calendar/` | JVM 测试 |
| `app/lib/calendar/` | Dart 通道 |
| `app/lib/state/calendar_state.dart` | 开关和权限状态 |
| `app/lib/ui/calendar/` | 设置页 |
| `app/test/calendar/` | Flutter 通道测试 |
| `docs/design/calendar-mcp.md` | 设计、边界和限制 |

## 不能单独编译

这些文件依赖 dsh-go 主体的 `PhoneGuest`、`PhoneBridge`、`McpServer`、`ToolOutput`、`Args`、设置索引和 l10n。要跑测试，得把目录放回 dsh-go 工作副本的对应路径，命令见 [INTEGRATION.md](INTEGRATION.md)。

## 验证情况

跑通的部分：

- Android JVM：103 个套件、778 个用例，0 失败（在 dsh-go 工作副本里跑）
- `flutter analyze`：无问题

没通过的部分：

- Flutter 全量 `flutter test` **既没跑完，也没全过**。跑了一部分后手动停下，已经看到两类失败：
  - golden 截图对不上。当时 `DSH_TEST_FONT_DIR` 指向的字体目录放的是 Windows 的微软雅黑，仓库里的 golden 按 Noto CJK 生成，字形不同，成批比对失败。
  - 一部分本地 HTTP 和审批相关用例 30 秒超时，和沙箱里的网络环境有关。
- 失败截图留在 dsh-go 的 `app/test/**/failures/`，17 个目录、约 1100 个文件，没有删。
- 把字体换成 Noto CJK、换到网络正常的环境后要重跑一遍，才能给出全量结论；在那之前不能说 Flutter 侧测试通过。

真机还没验：权限拒绝、选日历、同 key 更新、只删 DSH 事件、提醒、关开关后重启、安全模式不挂载。
