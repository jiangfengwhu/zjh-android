# 紫金花 Android 壳

维护原生能力、Codegen 桥接、APK、运行时契约和依赖补丁。SDK 与 RN 宿主在 zjh-dynamic，应用源码在 zjh-plugin，服务端在 zjh-server。

android/ 为 Gradle 工程，specs/ 为 Codegen 接口，sdk/bridge/ 为桥接 SDK，patches/ 为 postinstall 自动应用的补丁，tools/ 为资源准备与运行时发布工具。

## 构建与安装

需要 Node.js >=22.11.0、JDK 17+、Android SDK，配置 JAVA_HOME、ANDROID_HOME。

1. 首次安装或锁文件变化时执行 npm ci。
2. 执行 npm run prepare:apk，准备线上宿主 vendor/app 与 Jolly 公开版本，校验包哈希与运行时契约。不要求检出其他仓库。
3. 在 android/ 执行 ./gradlew :app:assembleRelease，Windows 使用 gradlew.bat。
4. 输出 android/app/build/outputs/apk/release/app-release.apk；使用 adb install -r <apk> 覆盖安装并保留数据。

发布新的 APK 版本时递增 config/apk-version.json 的 versionCode，运行时指纹由此生成；仅更新动态包不改变它。普通 UI 修改走动态发布，原生实现或依赖变化才需要新 APK。

保留 Gradle、CMake/Ninja 和 .cache/apk 缓存，不在常规构建前 clean 或无故重装依赖。固定工具链和架构；prepare:apk 优先复用哈希匹配的本地资源，缺失时下载。缓存不替代兼容与完整性检查。

## 发布运行时契约

维护者登录 Cloudflare Wrangler 后执行 npm run publish:runtime，发布桥接 SDK 与 /v1/runtime。使用维护者管理权限，动态包凭据不能修改契约。原生依赖变更需同步本仓库与 zjh-dynamic 的版本和锁文件，协调配套 APK、宿主与服务端上线。

纯文档修改不构建或发布。[原生补丁说明](patches/README.md)。
