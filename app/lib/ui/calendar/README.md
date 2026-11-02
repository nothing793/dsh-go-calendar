# calendar

设置 → 日历：启用内置 `calendar` MCP、申请系统日历权限、选择写入哪个可写日历。

页面只调用 `dsh/calendar` MethodChannel；不直接访问 Android Calendar Provider。
