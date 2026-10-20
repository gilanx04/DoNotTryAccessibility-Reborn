> [!WARNING]
> **Unofficial builds.** This is a fork maintained by [@gilanx04](https://github.com/gilanx04). It only adds automated builds and GitHub Releases; the application source code comes from the upstream project. It is **not affiliated with or endorsed by** the original author.
>
> **Download:** [Releases](https://github.com/gilanx04/DoNotTryAccessibility-Reborn/releases) · **Upstream:** [zjz-Beiming/DoNotTryAccessibility-Reborn](https://github.com/zjz-Beiming/DoNotTryAccessibility-Reborn)
>
> Release APKs are signed with a separate release key. Uninstall any previous build before installing.

# DoNotTryAccessibility Reborn

**让被拉黑的应用"以为"设备上没有开启任何无障碍服务。**

基于 [libxposed Modern API 102](https://github.com/libxposed/api)（LSPosed 2.2.0+）全新重写的 LSPosed 模块。Hook 点位设计参考 [Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility)。

## 它解决什么问题

部分应用会扫描设备的无障碍服务列表（用于风控、反自动化检测或广告 SDK 探测）。本模块在系统框架层拦截这些查询，让黑名单内的应用读到"什么都没开"的假结果——**被拉黑的应用不再享有读取你无障碍状态的功能**。

## 功能特性

- 拦截 `AccessibilityManagerService.addClient` / `getEnabledAccessibilityServiceList`，黑名单应用读不到任何已启用服务
- 拦截 `ContentProvider$Transport.call`，改写 `Settings.Secure` 中 `ENABLED_ACCESSIBILITY_SERVICES` / `ACCESSIBILITY_ENABLED` 的读取结果（兼容新旧两种 call 签名，不依赖固定参数下标）
- **深度隐藏**（可选）：连"已安装的无障碍服务列表"（`getInstalledAccessibilityServiceList`）都一并隐藏
- 模块内置配置界面：搜索应用、勾选黑名单、深度隐藏开关、**激活状态自检**
- 配置经 LSPosed RemotePreferences 下发，**改动实时生效**，无需重启
- 支持 libxposed API 102 热重载（模块更新后无需重启设备）

## 系统要求

| 项目 | 要求 |
|------|------|
| Android | 8.0+（minSdk 26） |
| Root | Magisk / KernelSU 等任意方案 |
| 框架 | LSPosed 2.2.0+（API 102）或 Vector v2.2+ |
| 作用域 | 系统框架 + 模块自身（`staticScope`，启用即自动应用） |

## 实现原理

所有拦截逻辑只运行在 system_server 内（作用域为系统框架），通过 `Binder.getCallingUid()` 识别调用方身份，只对黑名单应用返回伪造数据：

| Hook 点 | 行为 |
|---------|------|
| `AccessibilityManagerService.addClient` | 黑名单应用注册无障碍客户端时直接返回 0（按返回类型 int/long 自适应） |
| `AccessibilityManagerService.getEnabledAccessibilityServiceList` | 返回空列表 |
| `AccessibilityManagerService.getInstalledAccessibilityServiceList` | 深度隐藏开启时返回空 `ParceledListSlice` |
| `ContentProvider$Transport.call` | 按参数值定位 authority/method/key，改写 `_value` 并将 `_generation_index` 置 -1 绕过 Settings 缓存 |

相比原版的修复与改进：

- 修复原版 `call` 拦截中 `&&` 短路导致的判断漏洞（authority 匹配但 method 不匹配时误放行）
- 共享 uid 场景改用 `getPackagesForUid`（原版 `getNameForUid` 拿不到共享 uid 的真实包名，会漏判）
- Hook 匹配不绑定参数个数，抵御未来 Android 版本签名漂移导致静默失效
- 热重载时复用首次缓存的 system_server 类加载器
- 新增激活状态自检与深度隐藏

## 构建

需要 JDK 21+ 与 Android SDK（compileSdk 37）。

```bash
# 首次构建前，若仓库中无 gradle wrapper 二进制，先生成：
gradle wrapper --gradle-version 9.5.1

# 构建
./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 发布包（R8 混淆，debug key 签名）
```

或直接用 Android Studio 打开项目。

## 使用

1. 安装 APK，在 LSPosed 管理器中启用本模块
2. **重启设备**（作用域包含系统框架，需重启生效）
3. 打开模块界面——顶部显示 Hook 激活状态
4. 勾选需要屏蔽的应用，即时生效；需要恢复读取时取消勾选即可

## 致谢

- [Nitsuya/DoNotTryAccessibility](https://github.com/Nitsuya/DoNotTryAccessibility) —— 原版模块与 Hook 点位设计
- [AoEiuV020/IAmNotDisabled](https://github.com/AoEiuV020/IAmNotDisabled) —— 同类思路项目
- [libxposed/api](https://github.com/libxposed/api) —— Modern Xposed API
- [LSPosed](https://github.com/LSPosed/LSPosed) —— 框架与模块生态

## License

本项目基于 [GPLv3](LICENSE) 发布。
