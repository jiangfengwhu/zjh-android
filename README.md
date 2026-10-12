# 生花 Android 壳

维护原生能力、Codegen 桥接、APK、运行时契约与依赖补丁。SDK/宿主在 zjh-dynamic，应用在 zjh-plugin，服务端在 zjh-server。维护边界见 [AGENTS.md](AGENTS.md)。

| 目录 | 职责 |
| --- | --- |
| android/ | Gradle 工程与原生实现 |
| specs/ | RN Codegen 接口 |
| patches/ | postinstall 自动应用的原生补丁 |
| tools/、config/ | 资源准备、发布与版本契约 |

## 构建与安装

需要 Node.js >=22.11.0、JDK 17+、Android SDK，配置 JAVA_HOME、ANDROID_HOME。

```sh
npm ci
npm run build:apk
```

输出 android/app/build/outputs/apk/release/app-release.apk，使用 `adb install -r <apk>` 覆盖安装并保留数据。build:apk 准备宿主包后检查构建记录；源码输入和内置包不变时直接复用 APK，否则使用 Gradle 增量缓存构建。未提交源码可构建验证，但不写可发布的复用记录。

Windows 构建音频原生依赖需要 Git Bash 及其 Unix 工具。若脚本提示找不到 mkdir/rm，需将 Git 安装目录的 usr/bin 和 mingw64/bin 加入当前构建进程 PATH，再运行 Gradle。

prepare:apk 下载线上宿主 vendor/app，按包身份复用缓存并检查长度与运行时契约，不内置 plugin 应用。应用预热与打开按 appId/releaseId 共享内存中的准备任务和已完成路径（最多 32 组）；命中后只确认缓存文件仍存在，缺失时重新准备；完整缓存直接执行，不重复下载或校验内容。原生最多同时准备 3 个作品，每个作品以 2 路下载 bundle 和资源，全局资源池最多 6 路；可见页与后台任务的名额由 [宿主下载调度](https://github.com/jiangfengwhu/zjh-dynamic/blob/main/README.md) 分配。下载不等待手势或 JS 空闲，客户端不做公开版本联网验签或下载文件 SHA-256 比对。制作进度页及角色视频属于宿主，视频从宿主媒体 CDN 加载，APK 不内置作品或重复角色视频。

Release 开启 R8 代码与资源裁剪、内置 bundle 压缩。原生能力随 APK 编译，通过 Codegen 桥接提供给宿主；原生共享库保持直接加载，不以安装后额外解压换取下载体积。

作品准备有 90 秒总时限；网络连接超时 10 秒、读取超时 15 秒，连续 15 秒不足 32 KiB 的传输由看门狗关闭。取消和失败关闭同任务的连接并停止其他资源 worker，已完整下载的文件保留；不做断点续传。桥接成功的 Promise 直接在工作线程 resolve，具体 UI 操作仍由其实现调度到主线程。

作品资源使用固定 64 个分段锁，同一个 appId/资源哈希始终使用同一把锁，锁数量不随下载历史增长；不同资源映射到同一分段时串行执行。

作品准备通过下载池异步回调完成，不占用桥接线程等待；取消、AI 请求、应用执行与通用调用分用线程池，应用执行仍串行。宿主更新的 vendor/app 使用两路下载池，流式写入临时文件；全部完成后才暂存清单。任一下载失败会关闭其他连接，并等待所有 worker 退出后允许重试，保留已完整下载的文件。

AI 请求体及附件校验由 dynamic 的 RN 层维护。原生 complete 只在已配置引擎地址下发送 RN 提供的 body 和相对 path，注入保存的密钥，禁止重定向，并限制请求及响应大小、处理取消和超时。文本 SSE 仍在原生解码，约每 40ms 合并增量通知 RN；请求只接受 RN 组装的 body，不维护旧 messages 信封转换。新增请求参数不在 Kotlin 枚举，已支持 Chat Completions 与 Responses 的文本及 SSE 响应，新增响应协议需协调解码器。

锁文件不变时复用依赖与 Gradle、CMake/Ninja、.cache/apk 缓存，不常规 clean；仍须检查运行时兼容与文件长度，不进行完整性哈希校验。

## 发布

维护者登录 Wrangler，提交源码并保持工作区干净后执行：

| 命令 | 用途 |
| --- | --- |
| npm run publish:runtime | 发布 /v1/runtime 运行时契约，需管理权限 |
| npm run publish:apk | 校验版本、准备资源、构建并发布 release APK |
| npm run publish:apk -- --skip-build | 仅发布有匹配构建记录的 APK，禁止重新构建 |

APK 版本维护在 config/apk-version.json；新版本递增 versionCode，不覆盖同版本线上 APK。publish:apk 按 SHA-256 上传既有 R2 的 v1/apk/<hash>.apk，确认可下载后写入清单，记录版本、体积、运行时指纹及干净源码提交。

新 APK 的发布顺序为：递增版本并提交源码 → publish:runtime → 在 zjh-dynamic 发布对应运行时的宿主包 → publish:apk。运行时指纹随 versionCode 改变，prepare:apk 要求线上宿主包匹配当前 APK 版本；不能仅递增版本后直接发布 APK。

UI 与纯 JS 的 SDK API 变化走 dynamic 发布，不因 SDK 大版本变化发 APK。作品 SDK 兼容与 SDK 发布顺序见 [dynamic README](https://github.com/jiangfengwhu/zjh-dynamic/blob/main/README.md)。宿主与 APK 按运行时版本、RN/Hermes ABI 和原生依赖检查兼容；原生变化需配套 APK 并协调 SDK、宿主与服务端。纯文档修改不构建或发布。

## 更新

宿主请求 `/v1/updates?apkVersionCode=<本机版本>`，同时检查 APK 与动态清单。新版 APK 由用户点击后在应用内下载、确认文件长度，再调用 installPackage 打开系统安装界面；不跳浏览器、不静默安装，覆盖安装保留数据。

本地通知由 ShellAlarms 使用 AlarmManager 与 NotificationManager 实现，不引入通知第三方运行时。通知点击沿用 MainActivity 和宿主深链启动流程，不自行启动 ReactHost；事件先持久化，应用订阅后处理并确认。原生操作按钮可直接延后或取消提醒，日历重复规则在重启、系统时间与时区变化后重新计算；待触发提醒和事件按应用隔离，卸载时清理。作品通知按 appId 使用独立系统渠道，保留用户的声音、振动与开关设置；渠道名称来自宿主应用元数据，后台旧提醒不覆盖更新后的名称。状态查询同时检查宿主授权和应用渠道，设置入口定位对应渠道。公共接口与限制只维护在 SDK README。

交互能力由 SDK 可见状态及 MainActivity 前台状态共同约束，预加载作品不能弹起系统界面。BLE GATT按应用隔离连接、串行执行同一连接的读写，离开作品或切后台断开；NFC读写共享单个前台会话。传感器回调在独立HandlerThread按采样间隔发送，停止订阅后忽略迟到事件。语音查询拥有独立取消与超时，长文本按设备限制分段朗读；不依赖后台ReactHost。

系统分享接收使用两路IO复制临时content授权中的文件，并持久化待处理队列；用户选定作品后，在同一文件系统重命名至作品目录。转移意图先持久化，重启按目录状态完成或撤销转移；确认只移除事件，不删除作品文件。文件复制、追加和目录分页使用独立两路IO池，固定数量的路径锁协调提交，复制和追加在取消检查后替换目标；账号切换取消SDK文件任务与OCR。公共接口仅在SDK README维护，原生不向云端应用Agent公开宿主入口。

RN 由 AppCompatActivity 的 FrameLayout 直接承载 ReactSurface。宿主根布局完成且导航就绪后调用 bundleReady，确认包启动健康并释放原生启动图；这不代表首页数据加载完成或屏幕已经绘制。随后延迟 1.5 秒，在后台注册 WorkManager 更新任务、恢复提醒和执行常规旧包清理。后台任务可通过 Configuration.Provider 按需初始化 WorkManager。

Release 首次界面请求 whenReactReady 时，Application 启动 ShellStartup 后台任务（每进程一次）；通知接收器和 WorkManager 单独唤醒进程时不启动 RN：执行 prepareBoot，恢复 APK 内置包、处理失败回退、激活已暂存更新并准备固定 vendor 路径，再注册内置原生服务。后台准备磁盘包并注册服务，同时由主线程初始化 RN 原生库；两边完成后才启动 ReactHost 并创建 ReactSurface，避免提前捕获旧 vendor 路径。首次安装或 APK 升级直接使用 prepare:apk 写入 release-seed.json 的包身份复制内置包，不在手机上计算文件哈希，客户端不再计算或比对下载文件的 SHA-256，激活暂存更新只检查文件存在且非空；常规回收统一在 bundleReady 后延迟执行。重载后的 bundleReady 也触发回收，服务注册与提醒恢复每进程只执行一次。

准备失败或 app 包加载失败时退出启动图并显示原生恢复页；启动 30 秒仍未收到 bundleReady 时也显示恢复页，可重试或返回。准备失败重试重新执行准备任务，已有 RN 容器时重试重载 ReactHost；失败展示不会清除 boot.pending，成功仍由 bundleReady 确认。内置原生服务在启动准备时注册，JS 重载不重复注册。

上次启动未收到 bundleReady 时，下一次冷启动仍回退到上一套或 APK 内置包以保证可启动；未确认不等于实际崩溃，也可能是系统回收进程，因此不写版本黑名单。相同动态版本仍可自动暂存或手动重试，已有的旧黑名单文件不再参与判断。

原生 ReleaseCheck 仍由 WorkManager 每 30 分钟检查并暂存动态包；它与宿主页面的手动刷新、APK 安装流程分开。原生补丁及升级注意事项见下文。

启动性能使用 Release 真机多次测量，区分普通冷启动、首次安装或升级、更新激活与失败回退。JS 就绪日志不能代替实际显示时间；比较同类样本的中位数与 P95，不以单次升级启动推断提速。离线、深链和登录恢复也需验证。

APK 不绑定 SDK 大版本，宿主动态更新按原生运行时检查兼容。RN/Hermes ABI 或原生行为破坏旧应用兼容时，SDK 大版本必须同步递增；发布器阻止同大版本的 ABI 变化。APK 保留底层运行时和 vendor 配对校验；手机客户端下载文件按状态及长度确认完整，哈希字段用于缓存与版本定位。prepare:apk 等构建发布工具同样不校验文件哈希，缓存身份由版本清单和路径确定，避免仅凭相同长度误用另一版本。

分享使用 zijinhua.297782.xyz 的 HTTPS /posts/、/applications/ 链接，安装后由 Android App Links 打开。自定义 zijinhua://posts/<id>、zijinhua://applications/<id> 供网页按钮兜底。服务端 /.well-known/assetlinks.json 中的 SHA-256 指纹须匹配分发签名证书；更换签名时同步更新。验证使用 adb shell pm verify-app-links --re-verify com.zijinhua.app 与 adb shell pm get-app-links com.zijinhua.app，冷启动和 onNewIntent 均交给宿主路由处理。

应用系统交互由 ShellAppFeatures 实现，SDK 负责绑定应用身份、文件作用域和可见状态。语音、身份验证与传感器通过已有桥接异步返回，不等待占用桥接工作线程；图标解码与相册复制在后台执行。作品隐藏或宿主切后台时停止语音与传感器，系统身份验证可完成锁屏凭据流程。桌面入口按应用隔离，卸载作品时禁用；日历使用系统编辑页，安卓10+相册写入使用 MediaStore，旧安卓使用系统文件保存，不为这两项申请危险权限。公开接口与参数只维护在 SDK README。

## 原生依赖补丁

npm ci / npm install 的 postinstall 自动运行 patch-package。补丁改变原生实现，必须随新 APK 交付；升级依赖时重新核对，不能仅发布 JS 包。

| 补丁 | 当前行为与约束 |
| --- | --- |
| react-native-gifted-charts 1.4.78 | 渐变使用现有 Skia，保留颜色、位置和起止点参数；不再依赖 Linear Gradient 或 Expo，升级时核对柱状图渐变及尺寸变化 |
| react-native-video 6.19.3 | Android PlayerView 使用 TextureView，重置时保留最后一帧，支持滚动、变换与圆角裁切；影响所有视频实例，加入安全/DRM 视频前需复核 |
| react-native-screens 4.28.0 | 页面导航栏 trait 不再写窗口，由 MainActivity 初始化透明且可见的导航栏；不改变顶部状态栏、方向与键盘处理 |
| react-native-vision-camera-barcode-scanner 5.2.3 | 首次创建图片扫码器或相机扫码输出时初始化 ML Kit；宿主 Manifest 移除自动初始化 Provider，R8 保留反射注册器的无参构造 |

作品包与资源缓存位于 filesDir/application-bundles/<appId>，业务文件仍位于 filesDir/applications/<appId>。应用包只从包缓存或 CDN 加载，不读取业务目录或 APK 作品 seed；图库导出、快捷方式与小组件允许读取同一应用的包资源，不授予写入包缓存的权限。本地通知接收器共用单线程执行队列，不按每次广播创建线程。

文档正文提取在现有两路文档 IO 池执行，PDF 复用 PDFBox，DOC 使用 Apache POI，DOCX 流式解析正文 XML；只将有上限的文字返回 RN。超时或取消先响应用户，解析退出后释放工作槽，不增加权限。公共接口与限制见 SDK README。
