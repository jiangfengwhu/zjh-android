# 仓库边界

只维护 Android 静态壳。RN 业务在 zjh-dynamic，服务端在 zjh-server；不复制这些源码或历史到本仓库。
android/ 是原生工程；specs/ 是 Codegen 接口；sdk/bridge/ 是桥接 SDK；patches/ 是原生补丁；tools/ 是构建与运行时发布工具。
构建先运行 npm run prepare:apk 下载发布产物，不依赖动态仓库 checkout。
APK 版本只在发布新 APK 时通过 config/apk-version.json 递增，覆盖安装须保留数据。
不提交 node_modules、dist、构建缓存、凭证或机器专用 local.properties。本次迁移不新增测试文件。
