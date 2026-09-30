# 紫金花 Android 静态壳

负责 Android 原生能力、权限、桥接协议、更新加载与回退、APK 版本和原生依赖补丁。SDK 与 RN 宿主由 [zjh-dynamic](https://github.com/jiangfengwhu/zjh-dynamic) 维护，应用业务由 [zjh-plugin](https://github.com/jiangfengwhu/zjh-plugin) 维护，云端接口由 [zjh-server](https://github.com/jiangfengwhu/zjh-server) 维护。构建只下载动态发布产物，不要求检出其他仓库。

本仓库由本地维护者管理，不向云端 Agent 开放。云端应用任务只使用 zjh-plugin；原生能力需求由用户交给本地维护者处理，不向云端提供本仓库源码或管理凭证。

## 目录

| 路径 | 职责 |
| --- | --- |
| android/ | 标准 Android Gradle 工程；app/src/main/java 下按 bundle、host、plugin、shell 分层 |
| config/apk-version.json | APK 版本唯一配置 |
| specs/ | React Native Codegen 的原生模块接口声明 |
| sdk/bridge/java/ | 对外桥接 SDK 的编译接口 |
| patches/ | 原生依赖补丁，由 postinstall 应用 |
| tools/ | APK 资源准备、版本计算和运行时契约发布 |
| dist/ | 本地产物，不进入 Git |

## 构建

需要 Node.js 22+、JDK 17+、Android SDK，配置 JAVA_HOME 和 ANDROID_HOME。

1. 在仓库根目录执行 `npm ci`。
2. `npm run prepare:apk` 下载并校验线上 vendor/app 包。
3. 在 android/ 执行 `./gradlew :app:assembleRelease`，Windows 使用 gradlew.bat。
4. APK 输出到 android/app/build/outputs/apk/release/app-release.apk。需要安装时使用 adb install -r 保留数据。

只在发布新 APK 时递增 config/apk-version.json 的 versionCode。兼容指纹仅由 versionCode 生成，目录整理和 RN 发布不改变它。

## 运行时契约

壳维护者登录 Cloudflare Wrangler 后执行 `npm run publish:runtime`，发布桥接 SDK 与 /v1/runtime。该操作需要管理权限，动态包发布凭证不能修改契约。服务端的域名、KV、R2 绑定由 zjh-server 管理。

本仓库独立安装原生依赖；不使用动态仓库的 node_modules 或源码。

含原生实现的 RN 依赖由壳维护者协调两个仓库的版本与锁文件，并验证 APK 实际包含所需能力；仅更新动态依赖清单不代表 APK 已支持。纯文档修改无需构建 APK 或发布运行时契约。
