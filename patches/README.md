# 原生依赖补丁

npm ci / npm install 通过 postinstall 自动运行 patch-package；补丁改变原生实现，必须随新 APK 交付。

react-native-video 6.19.3 的 Android PlayerView 默认使用 SurfaceView，ViewType.TEXTURE 不生效。现有补丁改为 TextureView 并保留重置时的最后一帧，支持 Jolly 视频随列表滚动、变换和圆角裁切。

补丁作用于所有 Android 视频实例。升级该库或加入安全/DRM 视频前需重新审视补丁，不能仅发布 JS 包来更新它。
