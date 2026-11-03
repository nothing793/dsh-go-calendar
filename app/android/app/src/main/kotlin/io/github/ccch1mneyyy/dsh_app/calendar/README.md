# calendar

dsh-go 内置的「手机日历」MCP：把第三方 DSH 插件的日程写进 Android Calendar Provider。

- `CalendarToolCatalog.kt`：模型看到的 `list` / `upsert` / `remove` / `sync` / `list_owned` 工具 schema。
- `CalendarCore.kt`：参数校验、计划文件校验、同步差异、dry-run 令牌、时间解析和稳定的 key 规则。
- `AndroidCalendarStore.kt`：`CalendarContract` 的查询、插入、更新、删除与提醒。
- `CalendarGuest.kt`：写进 rootfs 的 MCP relay/patch，由引擎启动时挂载为 `calendar` 服务。
- `CalendarControl.kt`：开关、权限、选中日历、MCP bridge 生命周期。
- `CalendarPlugin.kt`：Flutter 的 `dsh/calendar` MethodChannel。

这个目录只做通用日历能力，不含任何校园业务。批量同步读取的计划文件由校园插件写到 `/root/.dsh/zju-campus/calendar-plans/`。
