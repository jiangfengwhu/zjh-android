# 仓库边界

这是 Android 静态壳仓库。RN 页面和 DEX 插件源码位于 zjh-dynamic，不在这里维护。
构建 APK 通过 scripts/prepare-apk.cjs 获取已发布的分包，禁止要求检出动态仓库才能构建。
APK 版本只在发布新 APK 时通过 mobile/apk-version.json 递增。保留覆盖安装时的数据。
不要提交 node_modules、构建缓存、发布凭证或机器专用 local.properties。
