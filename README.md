# SwiftBackupPrem（个人定制版）

基于上游 [s1ddhants1/SwiftBackupPrem](https://github.com/s1ddhants1/SwiftBackupPrem) 持续同步，并在上游功能之外维护本仓库自己的本地 Mod。上游负责主体功能与版本更新，本仓库通过 `mod/` 注入定制代码，尽量不直接改动上游源码。

## 本仓库定制内容

### 1. 自动匿名登录，绕过强制 Google 登录

针对 Swift Backup 的登录流程增加本地 Hook：

- 在 Swift Backup 启动进入 `IntroActivity` 时检查当前会话。
- 如果当前没有登录用户，则调用 Swift Backup 自身的匿名用户构造逻辑，创建匿名用户并写入本地会话。
- 将 `MFirebaseUser.isAnonymous()` 的结果调整为 `false`，绕过依赖“非匿名用户”状态的云功能入口检查。
- 已有会话不会被重复覆盖。

这项定制的目标是免去强制 Google 登录，让没有可用 Google 登录环境的设备也能进入应用并使用相应云功能。匿名身份与原 Google 账号身份并不相同；不要把匿名账号当作原账号备份的替代身份。

### 2. 云备份上传链路诊断探针

`mod/ModHook.kt` 中包含针对 Swift Backup 5.1.0（内部版本 v620）的临时诊断 Hook，用于追踪云备份上传流程：

- 上传状态机结果（`Lag8.c()`）
- 是否进入上传执行层及上传文件名（`g62.f()`、`g62.i()`）
- 通用 PUT 层调用（`mj1.e()`）
- WebDAV 分块 PUT 调用（`vq8.x()`）
- Firebase 同步读写结果（`cf3.a()`、`cf3.b()`）

日志标签为 `SBLocalProbe`。这些探针用于定位“界面显示 Uploaded，但没有实际 PUT 请求”等问题；它们是诊断用途，不代表已经修复所有云端上传问题。探针目标使用 v620 混淆类名，新版本可能自动跳过未匹配的探针。

## 定制代码与注入方式

- 定制源码集中在 `mod/`，主要文件为：
  - `mod/ModHook.kt`：当前 API 102 Hook 实现（匿名登录及上传链路探针）。
  - `mod/HookEntry.java`：早期独立 API 101 Hook 实现，保留作兼容参考；当前构建注入以 `ModHook.kt` 为准。
  - `mod/apply.sh`：将 Mod 注入上游工程。
- `mod/apply.sh` 会把 Hook 注入公共源码目录，并分别接入 normal / legacy 两种 flavor 的模块入口。
- 注入过程带有标记检查，重复执行不会重复插入。
- 上游同步时保留本仓库的 `mod/`、`.github/workflows/` 和本 README，避免定制内容被上游文件覆盖。

## 自动同步与构建

本仓库使用 GitHub Actions 跟踪上游更新：

1. 检查上游仓库更新。
2. 同步上游代码，同时保留本地 Mod、工作流和本 README。
3. 执行 Mod 注入并构建 normal / legacy 两种 flavor。
4. 执行构建产物检查。
5. 构建通过后按工作流发布规则生成本仓库 Release。

请以本仓库 Releases 页面提供的 APK 为准。上游版本、构建状态和本仓库发布状态以各自页面显示为准。

## 使用说明

1. 从本仓库 [Releases](https://github.com/klcb2010/SwiftBackupPrem/releases) 下载 APK 并安装。
2. 在 LSPosed / 兼容的 LibXposed 管理器中启用模块。
3. 确认模块作用域包含 Swift Backup（`org.swiftapps.swiftbackup`）。
4. 强制停止并重新启动 Swift Backup，检查模块是否生效。

模块需要兼容的 Root 与 LSPosed / LibXposed 环境。具体 Android 版本、框架 API 和 Swift Backup 版本兼容性，请以当前构建及实际测试结果为准。

## 上游项目

- 上游源码：[s1ddhants1/SwiftBackupPrem](https://github.com/s1ddhants1/SwiftBackupPrem)
- 上游 Releases：[SwiftBackupPrem Releases](https://github.com/s1ddhants1/SwiftBackupPrem/releases)
- 本仓库 Releases：[klcb2010/SwiftBackupPrem Releases](https://github.com/klcb2010/SwiftBackupPrem/releases)

## 免责声明

本仓库是个人维护的定制分支，非 SwiftApps 官方项目，也不代表上游作者立场。使用前请自行确认账号、备份数据和设备环境风险。涉及账号身份、云端备份及数据恢复的操作，建议先做好独立备份。
