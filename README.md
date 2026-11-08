# HideShot（XposedScreenshotMagic）

在 **HyperOS 4（Android 17）** 上，截图 / 录屏 / 投屏时把系统浮层从画面里去掉的 Xposed 模块。
纯系统侧实现，作用域只需要勾 **`system`（系统框架）** 一个，装上任何新 App 都不用再配置。

## 兼容性

| ROM | 状态 | 说明 |
|---|---|---|
| HyperOS 4 / Android 17 | ✅ 实测可用 | 当前 `hyperos4` / `main`（v1.8.2） |
| HyperOS 3 | ❓ 未验证 | 旧实现停在 tag `hyperos3-last`；没有设备验证，不再维护 |

HyperOS 4 把窗口 Surface 改成客户端创建（`useClientSurface()` 恒 true、`createSurfaceLocked()` 返回 null），
旧挂点失效，所以是重写而不是兼容升级。其他厂商 ROM 不要安装，窗口结构不同。

## 隐藏哪些

- 输入法
- 应用悬浮窗（`type=2038`）
- 画中画（`2038` + 父 Task `PINNED`）
- 从最近任务拖出来的悬浮小窗（`windowingMode=FREEFORM`）
- 安全中心侧边栏与浮层（`2026` / `2003`）
- 截图后的动画层、缩略图，以及**整个截图编辑器**
- 分屏和全屏**不隐藏**（刻意设计）

## 按需放行

设置页里六条规则各有一个开关；也可以按应用放行小窗 / 悬浮窗 / 画中画（三个维度独立，已放行的应用排在列表最前）。
改动通过 LSPosed 的 **Remote Preferences** 直接推到 `system_server`，毫秒级生效，
**不需要重启，也不需要重开窗口**。

## 原理（一句话版）

hook `WindowState.setClientSurface(SurfaceControl)`，对目标窗口的「父 Task 容器层 + 窗口层 + 客户端图层」
一起下 `Transaction.setSkipScreenshot(sc, true)`；判定变化时撤标同理。

## 安装

1. 安装 APK
2. LSPosed 里启用模块
3. 作用域**只勾「系统框架」**
4. 重启

## 构建

```bash
./gradlew assembleRelease        # 需要 JDK 21
```

release 签名依赖环境变量 `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`，密钥库放在 `app/keystore.jks`。
`gradle.properties` 里的 `android.enableResourceOptimizations=false` **不要删**：AGP 9 的资源优化会吞掉
`AndroidManifest.xml` 与 `resources.arsc`，产出的 APK 装不上。

## 已知限制

输入法打标后内容确实不参与截图，但它占的 `WindowInsets` 仍在，App 内容会被抬高、原位置留白。
这是 `setSkipScreenshot` 的固有语义，改不掉。
