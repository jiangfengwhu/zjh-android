# APK 构建缓存

普通 UI 和业务修改走动态发布，只有原生源码或原生依赖变化才需要新 APK。

本地 Gradle 构建缓存、daemon 和跨项目并行已启用。固定使用相同 JDK、Gradle、NDK、构建目录和 arm64-v8a 架构；不在常规构建前执行 clean。不变的 package-lock 不反复 npm ci，避免删除 RN 依赖目录中的 build/.cxx 原生增量产物。Gradle 构建缓存与 CMake/Ninja 的本地增量产物都应保留，前者不保证缓存所有外部原生编译任务。

`npm run prepare:apk` 每次读取线上宿主和 Jolly 公开版本清单，按 SHA-256 与大小验证，依次复用 APK 现有资源、`.cache/apk` 与相邻仓库本次发布产物。未命中才下载，单次下载限时 60 秒。内容不变时不重写资源，避免时间戳造成不必要的构建工作。缓存不替代线上版本、兼容性或哈希检查。

常规流程：先源码提交与发布，执行 prepare:apk，再在 android 目录执行 gradlew.bat :app:assembleRelease。首次增加原生依赖仍可能触发较多 C++ 编译；之后在同一工具链、相同依赖下复用增量产物。

暂不默认启用 configuration cache：当前自定义任务和第三方插件还需确认其兼容性。不绕过版本验证、lint 或打包检查。远程构建缓存等引入 CI 后再配置。
