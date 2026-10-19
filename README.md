# 芭芭农场助手（BabaFarm）

淘宝「芭芭农场」真机自动化助手：用 Android 无障碍服务（AccessibilityService）读取页面节点 + 派发真实手势，定时完成每日任务，尽量把肥料拿满。

> ⚠️ **合规提示**：本项目仅供个人学习研究。自动化操作可能违反淘宝用户协议，存在账号风控风险（实测连续多轮操作会触发滑块验证码），请自行评估使用。

## 功能流程

每天定时（默认 07:00，备用 07:10）或手动触发后，按顺序执行：

1. 冷启动淘宝 → 清理启动弹窗
2. 进入芭芭农场（首页图标 / 我的淘宝两条路径）
3. 领取每日肥料（按钮含「明日」则跳过）
4. 日常任务（只做淘宝内、任务名命中白名单的浏览类任务）
5. 挖肥料（只点一次）
6. 施肥循环：施肥到「可施肥 0 次」→ 领等级条上方肥料 → 有肥料继续
7. 看小说领肥料：打开「看书剧领肥料」→ 随机点一本书 → 阅读 6 分钟（每 5 秒翻页）→ 返回
8. 收尾：关闭浮层，确认停在农场首页

## 代码结构

| 路径 | 说明 |
|---|---|
| `app/src/main/java/com/hilogic/babafarm/accessibility/FarmWorkflow.kt` | 业务状态机：8 步流程、任务面板解析、看小说、浮层处理 |
| `.../accessibility/FarmAccessibilityService.kt` | 无障碍服务宿主、单轮互斥、页面判据 |
| `.../core/Locator.kt` | 节点查找、点击/手势、页面文案快照 |
| `.../core/Selectors.kt` | 全部文案候选、阈值、白名单（改版时主要改这里） |
| `.../core/ScriptLog.kt` | logcat + 文件双通道日志（应用内可查看最近 300 行） |
| `.../service/FarmForegroundService.kt` | 前台服务、WakeLock、18 分钟看门狗、风控告警通知 |
| `.../schedule/AlarmScheduler.kt` | `setAlarmClock` 主/备用闹钟、开机重排 |
| `.../receiver/*.kt` | 闹钟触发、开机/更新重排 |
| `.../ui/MainActivity.kt` | 状态诊断、手动执行、节点快照、日志查看 |
| `tools/` | 真机勘探用 adb/Python 脚本（dump 节点、探弹窗、验证滚动等） |
| `DEPLOY.md` | **部署与实测手册（最重要）**，含所有踩坑记录与 2026-10-19 修复 |
| `TASKS_PLAN.md` | 早期方案，已过时，仅作历史记录 |

## 构建

环境要求：

- JDK **17**（AGP 8.1.4 不支持 JDK 8）
- Gradle 8.4
- Android SDK（`compileSdk 34`、`build-tools 34.0.0`）

`local.properties` 里配置 SDK 路径（`sdk.dir=...`，注意 Windows 路径转义）。

命令行构建（PowerShell）：

```powershell
$env:JAVA_HOME='<JDK17 路径>'
$env:ANDROID_HOME='<Android SDK 路径>'
# 若本机 ~/.gradle 不可写，可用工程内 .gradle-home（见 DEPLOY.md 〇之四）
$env:GRADLE_USER_HOME='<工程>\.gradle-home'
$env:ANDROID_USER_HOME='<工程>\.android-home'
gradle --offline assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用前必做

1. 打开应用 → 「开启无障碍服务」→ 系统列表里打开「芭芭农场助手」
2. 「加入电池优化白名单」（三星/小米/华为等还需要在系统电池设置里允许后台运行）
3. 最近任务里锁定本应用，防止被一键清理
4. 建议自动化手机**不设安全锁屏**（应用无法自动解锁；锁屏会导致 07:00 自动执行失败）
5. 首次使用先在应用内点「立即执行一次」，查看「运行日志」确认流程

## 当前状态（2026-10-19）

- 已在三星 SM-G9550 / Android 9 / 淘宝 10.66.40 上跑通完整 8 步
- 最终验证（15:56 手动，429 秒）：无风控；看小说自动切回「小说」tab 后成功进入阅读页，读满 6 分钟（68 页）并回到农场首页
- 实测单轮约 7~9 分钟（含看小说 6 分钟）
- 已知问题：
  1. 浏览类任务**连续第 2 轮偶尔不计数**（手动单轮可计数，疑似服务端风控/重复页去重），当前放宽为连续 2 轮不推进才放弃
  2. 连续多轮自动化会触发淘宝**滑块验证码**；程序会检测并中止 + 发通知，但无法自动通过
  3. 任务名白名单需按淘宝实际面板维护（任务名每天会变），未命中时日志会明确提示
  4. 淘宝面板/文案改版频繁（看小说 tab 状态、榜单位置、浮层等），长期需要按实测更新

详细实测数据与修复记录见 [DEPLOY.md](DEPLOY.md) 的「〇之四」。
