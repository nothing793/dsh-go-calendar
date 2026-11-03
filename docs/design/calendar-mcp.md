# 内置手机日历 MCP

2026-11-03。给本机插件一个窄接口，把校园日程写进 Android 系统日历；不在 App 市场单独发插件。

## 边界

- 只在本机引擎、正常模式、用户打开 `设置 › 日历` 并授予 `READ_CALENDAR` / `WRITE_CALENDAR` 时挂载。
- 与「手机操控」分开：不要求无障碍、不读屏幕，也不复用它的开关。
- 模型看到五个固定工具：`mcp__calendar__list`、`mcp__calendar__upsert`、`mcp__calendar__remove`、`mcp__calendar__sync`、`mcp__calendar__list_owned`。
- 只操作带本 App 包名和 `dsh-calendar://event/<key>` 的事件。不列举、不读取、不修改用户自己建的事件。
- 不做双向同步；系统日历里改过的事件不会被读回校园插件。

## 结构

| 位置 | 作用 |
|---|---|
| `calendar/CalendarToolCatalog.kt` | 固定的工具 schema，进入每个模型请求 |
| `calendar/CalendarCore.kt` | 参数校验、计划校验、差异计算、确认令牌、时间归一化；不碰 Android API |
| `calendar/AndroidCalendarStore.kt` | Calendar Provider 的查询、插入、更新、删除与提醒 |
| `calendar/CalendarGuest.kt` | 写进 rootfs 的 relay 和 `--patch` overlay，启动时挂成 `calendar` MCP |
| `calendar/CalendarControl.kt` | 开关、权限、选中日历、bridge 生命周期 |
| `calendar/CalendarPlugin.kt` | Flutter 的 `dsh/calendar` MethodChannel |
| `app/lib/calendar/` | Dart 通道 |
| `app/lib/ui/calendar/` | 设置页 |
| `app/lib/state/calendar_state.dart` | 开关、权限和重启状态 |

启动顺序与手机操控相同：`CalendarControl.prepareLaunch()` 先检查开关、安全模式、权限和选中日历，再启动 abstract unix socket bridge、写 `bridge.json`；`DshProcess` 在 `dsh web` 后追加 `--patch /opt/dsh-app/calendar/calendar.patch.yml`。引擎退出或启动失败时关闭 bridge 并删除配置。

## 工具

### `list`

只返回可写日历的 id、名称、账号和是否默认。选中日历在 App 设置里保存；未选择时默认第一个可写日历。

### `upsert`

必填 `key`、`title`、`begin`，可选 `end`、`all_day`、`calendar_id`、`location`、`description`、`reminder_minutes`。

- `key` 只允许 ASCII 字母、数字、点、下划线、冒号和短横线，1–128 字符。同一 key 再写会更新原事件。
- 定时事件按手机时区；显式 `+08:00` 也支持，适合校园日程。
- 全天事件用 UTC 零点，`end` 是结束日期且不含当天；省略时只占 `begin` 当天。
- `reminder_minutes` 最多 8 个，每项 0–40320 分钟。
- `calendar_id` 必须指向可写日历；显式传入时写入该日历。

### `remove`

按 `key` 删除；可选 `calendar_id` 限定在某个日历。找不到时返回成功和「可能已经删掉了」，不报错。

### `list_owned`

只列本 App 写入的事件。可选 `scope` 和 `calendar_id`；最多返回 500 条，超过时 `truncated=true`。返回 `key`、标题、开始/结束、日历 id 和是否全天，用于核对同步结果，不读用户事件。

### `sync`

按校园插件生成的计划文件批量同步。参数：

- `plan_id`：必填，只能是字母、数字和短横线，1–64 字符。
- `dry_run`：默认 `true`。只预览，返回 `changes`、`warnings` 和 `token`。
- `token`：`dry_run=false` 时必填。令牌绑定计划内容、目标日历、`migrate` 和当前已有事件，10 分钟过期，只能使用一次。
- `calendar_id`：目标日历；省略时用设置里选中的日历。
- `migrate`：换日历后是否清理其他日历里本学期的旧事件；默认 `false`。

计划文件从 `/root/.dsh/zju-campus/calendar-plans/<plan_id>.json` 读取。根目录固定，`plan_id` 中的路径符号会被拒绝；文件大小上限 4 MiB，事件上限 1000 条。

计划文件字段：

```text
version=1
scope
label
range { from, to }
events[]
preserve_kinds[]
warnings[]
```

每条事件必须有 `key`、`title`、`begin`，可选 `kind`、`end`、`all_day`、`location`、`description`、`reminder_minutes`。所有 key 必须以 `scope:` 开头且不能重复。

同步按 key 对齐：

1. 目标日历里已有同 key 事件：内容不同就更新，内容相同计入 `unchanged`。
2. 其他日历里有同 key 事件：更新时移到目标日历。
3. 没有同 key 事件：新建。
4. 目标日历里本次计划没有的旧事件：删除。
5. 其他日历里本次计划没有的旧事件：`migrate=true` 时删除，否则保留并在 `warnings` 里说明。
6. `preserve_kinds` 中的事件不删除，用于某个待办来源本次刷新失败时保留旧截止。

正式写入先做新增和更新。只有这一步全部成功后才执行删除；任何写入失败都会跳过删除，避免旧事件先被清掉。返回 `changes` 是实际完成的条数，失败原因在 `errors`。

## 稳定标识与隐私

事件写入：

- `CUSTOM_APP_PACKAGE = io.github.ccch1mneyyy.dsh_app`
- `CUSTOM_APP_URI = dsh-calendar://event/<URL-encoded key>`

更新和删除都按这两个字段查询。其他事件即使标题相同也不会被碰。`list_owned` 和 `sync` 只读取本 App 包名下的行。

## 已知限制

- 没有真机验证：Provider 写入、权限拒绝、系统日历 App 的显示和提醒尚未在 Android 设备上跑过。
- 不处理重复规则、参与者、附件和日历同步适配器；这些字段由系统日历自己管理。
- 用户删掉事件后，同一 key 再 sync 会新建，而不是恢复。
- 换日历、关闭开关或安全模式都要重启引擎才改变已挂载的 MCP；关闭开关会立即停止 bridge，后续调用失败。
- 计划文件目前只从校园插件的固定目录读取；插件若改了 `DSH_CALENDAR_PLAN_DIR`，需要同时改这里的路径。
- 确认令牌只能约束模型先做 dry-run，不能证明用户已经看过预览；用户确认仍由工具说明和界面流程保证。
