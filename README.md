# 紫金花 Android 壳

维护原生能力、Codegen 桥接、APK、运行时契约与依赖补丁。SDK/宿主在 zjh-dynamic，应用在 zjh-plugin，服务端在 zjh-server。维护边界见 [AGENTS.md](AGENTS.md)。

| 目录 | 职责 |
| --- | --- |
| android/ | Gradle 工程与原生实现 |
| specs/、sdk/bridge/ | Codegen 接口与桥接 SDK |
| patches/ | postinstall 自动应用的原生补丁 |
| tools/、config/ | 资源准备、发布与版本契约 |

## 构建与安装

需要 Node.js >=22.11.0、JDK 17+、Android SDK，配置 JAVA_HOME、ANDROID_HOME。

```sh
npm ci
npm run prepare:apk
cd android
./gradlew :app:assembleRelease
```

Windows 使用 gradlew.bat。输出 android/app/build/outputs/apk/release/app-release.apk，使用 `adb install -r <apk>` 覆盖安装并保留数据。

prepare:apk 下载线上宿主 vendor/app 并校验哈希与运行时契约，不内置 plugin 应用。Jolly 制作进度页及视频属于宿主；准备资源时清理旧 Jolly 应用 seed。

Release 开启 R8 代码与资源裁剪、内置 bundle 压缩；独立 DEX 插件使用的公共桥接类型保留名称与成员。原生共享库保持直接加载，不以安装后额外解压换取下载体积。

锁文件不变时复用依赖与 Gradle、CMake/Ninja、.cache/apk 缓存，不常规 clean；仍须校验兼容与完整性。

## 发布

维护者登录 Wrangler，提交源码并保持工作区干净后执行：

| 命令 | 用途 |
| --- | --- |
| npm run publish:runtime | 发布桥接 SDK 与 /v1/runtime，需管理权限 |
| npm run publish:apk | 校验版本、准备资源、构建并发布 release APK |

APK 版本维护在 config/apk-version.json；新版本递增 versionCode，不覆盖同版本线上 APK。publish:apk 按 SHA-256 上传既有 R2 的 v1/apk/<hash>.apk，确认可下载后写入清单，记录版本、体积、运行时指纹及干净源码提交。

UI 变化走 dynamic 发布；原生变化需配套 APK 并协调 SDK、宿主与服务端。纯文档修改不构建或发布。

## 更新

宿主请求 `/v1/updates?apkVersionCode=<本机版本>`，同时检查 APK 与动态清单。新版 APK 由用户点击后在应用内下载、校验，再调用 installPackage 打开系统安装界面；不跳浏览器、不静默安装，覆盖安装保留数据。

原生 ReleaseCheck 目前仍由 WorkManager 每 30 分钟检查并暂存动态包；它与宿主页面的手动刷新、APK 安装流程分开。原生补丁及升级注意事项见 [patches/README.md](patches/README.md)。

APK 声明可加载的 SDK 大版本，宿主动态更新必须属于该大版本。RN/Hermes ABI 或原生行为破坏旧应用兼容时，SDK 大版本必须同步递增；发布器阻止同大版本的 ABI 变化。作品兼容由宿主按 SDK 大版本和最低 API 判断，APK 保留底层运行时、下载哈希和 vendor 配对校验。
