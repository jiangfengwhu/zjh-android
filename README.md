# 紫金花 Android 静态壳

本仓库维护 Android 工程、原生依赖和补丁、权限、桥接协议、DEX 加载器、更新回退、APK 版本，以及 Cloudflare 发布/云端会话服务。RN 与 DEX 应用源码在 [zjh-dynamic](https://github.com/jiangfengwhu/zjh-dynamic)。本仓库不拉取动态仓库源码或历史，只下载发布产物。

## 构建 APK

需要 Node.js 22+、JDK 17+、Android SDK，并配置 `JAVA_HOME` 和 `ANDROID_HOME`。

1. `npm ci --prefix mobile` 安装原生构建依赖并应用补丁。
2. `npm run prepare:apk` 下载线上 vendor/app 包并校验字节数、SHA-256 和 APK 兼容版本。
3. 在 `mobile/android` 执行 `gradlew.bat :app:assembleRelease`（Linux/macOS 使用 `./gradlew`）。
4. `adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk` 覆盖安装并保留数据。

APK 的版本号唯一配置位于 `mobile/apk-version.json`。仅在发布新 APK 时递增 `versionCode`，兼容版本仍只由该版本号生成。单纯拆分仓库不改变版本号。

## 发布新运行时

登录 Cloudflare Wrangler 后执行 `npm run publish:runtime`，编译并发布桥接 SDK 和 `/v1/runtime` 契约。动态仓库读取契约打包 RN/DEX；随后按上面的构建流程将对应 RN 包装进 APK。运行时契约只能通过壳维护者的 Cloudflare 管理权限发布，动态发布接口不能修改它。

## 服务端

`npm ci --prefix worker`，然后在 `worker` 执行 `npm run deploy`。保留现有 Worker 域名、KV、R2 和已配置的 Secrets，不把凭证放入 Git。

App 的 Jules 代理只返回 `zjh-dynamic` 来源，并拒绝其他仓库的会话操作。GitHub/Jules 集成的仓库授权仍由账户管理员管理，本仓库不向动态仓库提供源码或历史。

迁移来源为原 zijinhua 工作区当前快照；两个新仓库独立建立历史，避免动态仓库历史中保留 Android 源码。
