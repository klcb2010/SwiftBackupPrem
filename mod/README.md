# Swift Backup NoLogin（LSPosed 模块，libxposed API 101）

绕过 Swift Backup **强制 Google 登录**：自动匿名登录 + 解除 `isAnonymous` 云功能门禁。

- 适配目标：`org.swiftapps.swiftbackup` 5.0.8 (v603)
- API：libxposed **101**（`io.github.libxposed:api:101.0.1`），LSPosed 1.9+ 加载
- 依赖：本地 jar（`app/libs/libxposed-api-101.0.1.jar`），**无需联网拉依赖**

## 效果

| 场景 | 原版 | 本模块 |
|---|---|---|
| 无 Google 框架 / 账号被封（Firebase Auth USER_DISABLED） | 卡在登录页 | 启动自动注入匿名会话，直接进主界面 |
| 云同步（WebDAV 等） | 要求登录有效账户 | 匿名被伪装为非匿名，门禁放行 |

## 原理

Swift Backup v5 的登录状态链：

```
common.a3.i()  ←  是否"有用户"（主界面门禁）
    └ anonymous.a.e()  ←  读会话单例 common.V.getNon()（本地持久化），空则查 FirebaseAuth
common.a3.c() / g()  ←  是否"匿名用户"（云同步 / 多线程下载门禁）
    └ MFirebaseUser.isAnonymous()
```

模块做两件事（均等价于验证过的 smali patch v2，且无需点击登录按钮）：

1. **自动匿名登录**：hook `IntroActivity.onCreate(Bundle)`（HIGHEST 优先级、before），
   会话为空时调用官方匿名用户构造器 `anonymous.a.b.d()` 生成
   `MFirebaseUser(anonymous@swiftbackup.app)` 并写入 `common.V.setNon()`。
   官方匿名登录本身纯本地（不碰 Firebase/网络），写入后 `a3.i()` 短路通过。
2. **解除云门禁**：hook `MFirebaseUser.isAnonymous()` 恒返回 `false`。
   所有"是否匿名"消费方（`a3.c()`、`a3.g()`、home 协程等）随之放行。

## 模块文件规范（API 100+，不再用 meta-data）

```
app/src/main/resources/META-INF/xposed/
├── java_init.list   # 入口类：com.unlocksb.module.HookEntry
├── scope.list       # 作用域：org.swiftapps.swiftbackup
└── module.prop      # minApiVersion=101 / targetApiVersion=101 / staticScope=true
```

## 构建

用 Android Studio 打开工程根目录，直接 Build APK（Debug 即可，无需签名配置；
模块安装不校验签名）。产物：`app/build/outputs/apk/debug/app-debug.apk`

> 无需联网：唯一依赖 `app/libs/libxposed-api-101.0.1.jar` 已随工程提供
> （来源：Maven Central `io.github.libxposed:api:101.0.1` 的 classes.jar）。

- AGP 8.2.2 / Gradle 8.2+（可按你本地缓存调整 `build.gradle.kts` 中的插件版本）
- Java 17（`compileOptions` 已设）
- 若混淆（release 构建）：`proguard-rules.pro` 已含官方规则

## 安装与启用

1. 安装模块 APK（与 Swift Backup 共存）
2. LSPosed → 模块 → 启用 **Swift Backup NoLogin** → 勾选作用域
   `org.swiftapps.swiftbackup`（scope.list 已预设，勾上模块即可）
3. 重启 Swift Backup（或重启手机）
4. 打开 Swift Backup：自动匿名登录 → 主界面；云同步/WebDAV 不再要求 Google 登录

## 卸载 / 回退

- 停用模块即回原版行为（本地已写入的匿名会话仍在，属正常登录态，可在
  设置里 Sign out）
- 云备份数据仍绑定匿名 UID（`anonymous@swiftbackup.app`），与 Google 账户
  备份互不通用（官方设计）

## 适配新版本

Swift Backup 主包类名未混淆，hook 点稳定；若新版结构变化，调整
`HookEntry` 中类名/方法名即可。版本边界：5.0.x（v6xx）。
