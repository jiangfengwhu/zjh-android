# Android 维护规则

只维护原生壳、桥接、权限、运行时契约和原生依赖补丁。SDK/宿主在 zjh-dynamic，应用在 zjh-plugin，服务端在 zjh-server；本仓库供本地维护者使用，不向云端应用 Agent 提供源码或管理凭据。

- 构建、安装和缓存流程见 README.md，不复制其他仓库源码。
- 原生依赖与 SDK 平台契约保持一致；新增能力需协调 APK、宿主及服务端。不能仅改动态清单就宣称 APK 支持。
- 新 APK 版本维护在 config/apk-version.json，覆盖安装保留数据，不自行清库或卸载。
- 修改原生实现执行所需资源准备、Codegen 和 APK 构建；默认不额外运行测试或预览。纯文档修改不构建或发布。
- 不提交依赖、构建产物、缓存、凭证或机器专用 local.properties。
