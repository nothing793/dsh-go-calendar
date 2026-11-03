# dsh-go-calendar

dsh-go 的「手机日历」内置 MCP，以及配套的校园插件改动。它让手机上的 DSH 引擎直接读写
Android 系统日历：单条事件用 `upsert` / `remove`，整学期课表用 `sync` 批量写入。

## 来源

- 宿主是 [dsh-go](https://github.com/ccch1mneyyy/dsh-go)，沿用其 AGPL-3.0（见 [LICENSE](LICENSE)）。
- 日历 MCP 的 Kotlin 实现在 dsh-go 的 `app/android/.../calendar/`，通过 `--patch` 挂成
  MCP server `calendar`，模型看到的是 `mcp__calendar__*`。
- `dsh-zju-assistant` 校园插件负责把课表、考试、截止整理成计划文件；它对手机日历的适配
  （`plugin/host/schedule.mjs`、`phone_calendar` 命令）和本地出包脚本在
  [nothing793/dsh-zju-assistant](https://github.com/nothing793/dsh-zju-assistant)。
- dsh-go 目前不接受外部 PR，所以这份代码单独成仓，方便审查、跑测试和写安装说明。
  接入点见 [INTEGRATION.md](INTEGRATION.md)。

## 面向谁

- 在手机上用 dsh-go、想把本学期课表写进系统日历的人。
- 想给 dsh-go 加内置 MCP、但不想经过模型逐条搬几百条事件的插件作者。
- 想审这套实现、或在别的 Android 宿主里复用 Calendar Provider 适配的开发者。

不需要 root，不需要无障碍，不读屏幕。

## 能做什么

五个工具，全部只操作本 App 写入的事件：

| 工具 | 作用 |
|---|---|
| `mcp__calendar__list` | 列出可写的手机日历 |
| `mcp__calendar__upsert` | 按稳定 `key` 新增或更新一条事件 |
| `mcp__calendar__remove` | 按 `key` 删除本 App 写入的事件 |
| `mcp__calendar__sync` | 按校园插件生成的计划文件整批同步 |
| `mcp__calendar__list_owned` | 列出本 App 写入的事件，用于核对 |

`sync` 的流程是：

1. 读 `$DSH_HOME/zju-campus/calendar-plans/<plan_id>.json`（插件生成，不经过模型搬运）
2. 校验 scope、key、时间、字段、路径和 4 MiB 大小上限，最多 1000 条
3. `dry_run: true` 返回 `create / update / remove / unchanged` 计数、warnings 和一次性 token
4. 用户确认后带 token 写入；token 绑计划内容、目标日历和已有事件，10 分钟过期、只能用一次
5. 写入失败时跳过删除，避免旧事件先被清掉

其他行为：`preserve_kinds` 保留某类旧事件；换日历用 `migrate: true` 清理其他日历里的旧事件；
不同 scope 的事件互不影响。

## 安装

### 1. dsh-go（宿主）

本仓库是源码副本，不能单独编译——它依赖 dsh-go 主体的 `PhoneGuest`、`Args`、`ToolOutput` 等。
要么在 dsh-go 工作副本里按 [INTEGRATION.md](INTEGRATION.md) 合并，要么直接用带这套代码构建的 APK。

打开方式：设置 → 日历 → 授予日历读写权限 → 选一个可写日历 → 打开开关 → 重启引擎。
开关关闭或处于安全模式时，`mcp__calendar__*` 不会挂载。

### 2. 校园插件（产生计划文件）

插件分两层：`dsh-zju-assistant` 是组合包，`dsh-zju-campus` 是实现包。

```bash
# 在 dsh-zju-assistant 仓库里出包
node scripts/pack-local.mjs --target /root/workspace

# 在 dsh-go 的 guest 里安装
dsh plugin --profile web add /root/workspace/dsh-zju-assistant-0.1.2-local.tgz
```

注意两点，装错就表现为「插件页能看到组件，但点不进设置」：

- 组合包名必须是 `dsh-zju-assistant`，客户端把配置页挂在 `dsh-zju-assistant#<行 id>` 上；
- 组合包的 `package.json` 要有 `dsh.bundle.patch`，profile 的 `dsh.profile.bundles` 里要列出它。

### 3. 手机 WebView

插件设置页（填学号、密码）只在 DSH 网页界面里。系统 WebView 低于 Chrome 116 时，
网页界面会因为缺 `Promise.withResolvers` / `AbortSignal.any` 一直停在「重新连接中…」，
设置页也就打不开。

处理办法是 dsh-go 在页面加载前注入 polyfill（`app/assets/web/dsh_web_compat.js`，通过
`WebViewCompat.addDocumentStartJavaScript`）。**这份改动还没提交到 dsh-go 仓库，也没有进
任何 release**，官方的 dsh-go APK 里没有。用官方包时装完插件，组件和开关都在，但点不进
设置页，填不了学号密码。要绕开这一点，得自己按 dsh-go 的构建流程把这段注入带进去，或者
等改动进入 dsh-go 的发布版本。

## 验证情况

2026-11-03 在 HUAWEI Mate 70（CLS-AL00）上真机验证：

| 项 | 结果 |
|---|---|
| 权限授予、选可写日历 | 通过 |
| `upsert` 新建、`remove` 按 key 删除 | 通过 |
| `sync` dry-run 计数、token 写入 | 通过 |
| 同内容再 sync（幂等） | `unchanged` 全部命中，无重复 |
| 计划内容变更后旧 token 失效 | 通过 |
| `preserve_kinds` | 指定类型保留，其余删除 |
| 跨日历搬运 + `migrate` 清理孤儿事件 | 通过 |
| 300 条批量写入、提醒 | 300 条全部落库，100 条提醒正确 |
| 校园插件全链路（计划 → sync → 系统日历） | 151 课程 + 1 考试 + 3 截止写入成功 |

JVM 测试：104 个套件、787 个用例，0 失败（`gradle :app:testArm64DebugUnitTest`）。
Flutter 全量测试仍未跑通：golden 字体和部分本地 HTTP/审批用例在测试环境里失败或超时。
尚未验证：拒绝权限后的提示文案、安全模式不挂载。

## 边界

- 只在本机引擎、正常模式、用户打开「设置 › 日历」并授予 `READ_CALENDAR` / `WRITE_CALENDAR` 时挂载。
- 和「手机操控」分开：不要无障碍，不读屏幕，开关也独立。
- 只操作带本 App 包名和 `dsh-calendar://event/<key>` 的事件，不列举、不修改用户自己建的事件。
- 不做双向同步，系统日历里改过的事件不会读回来。
- token 只能约束模型先 dry-run，不能证明用户真的确认过。

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
