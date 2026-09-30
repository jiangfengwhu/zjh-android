# 仓库边界

只维护 Android 静态壳。SDK 与 RN 宿主在 zjh-dynamic，应用业务在 zjh-plugin，服务端在 zjh-server；不复制这些源码或历史到本仓库。

本仓库仅供本地维护者使用，不向云端 Agent 开放。不要把本仓库源码、原生补丁或 Cloudflare 管理凭证放入动态仓库或云端环境。云端提出原生需求时，由本地维护者按用户授权处理。

android/ 是原生工程；specs/ 是 Codegen 接口；sdk/bridge/ 是桥接 SDK；patches/ 是原生补丁；tools/ 是构建与运行时发布工具。

构建先运行 npm run prepare:apk 下载发布产物，不依赖动态仓库 checkout。

APK 版本只在发布新 APK 时通过 config/apk-version.json 递增，覆盖安装须保留数据。

含原生实现的 RN 依赖变更须协调动态仓库的清单和锁文件，确认 APK 支持后再交付动态功能。运行时契约发布使用本地管理权限，动态发布凭证不承担此职责。

按改动验证资源准备、Codegen 和必要的 APK 构建；默认不新增测试文件，用户明确要求时按其要求执行。纯文档修改无需构建、发 APK 或发布运行时契约。

不提交 node_modules、dist、构建缓存、凭证或机器专用 local.properties。
