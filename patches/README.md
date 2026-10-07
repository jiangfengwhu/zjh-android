# 原生依赖补丁

npm ci / npm install 的 postinstall 自动运行 patch-package。补丁改变原生实现，必须随新 APK 交付；升级依赖时重新核对，不能仅发布 JS 包。

| 补丁 | 当前行为与约束 |
| --- | --- |
| react-native-gifted-charts 1.4.78 | 渐变使用现有 Skia，保留颜色、位置和起止点参数；不再依赖 Linear Gradient 或 Expo，升级时核对柱状图渐变及尺寸变化 |
| react-native-video 6.19.3 | Android PlayerView 使用 TextureView，重置时保留最后一帧，支持滚动、变换与圆角裁切；影响所有视频实例，加入安全/DRM 视频前需复核 |
| react-native-screens 4.28.0 | 页面导航栏 trait 不再写窗口，由 MainActivity 初始化透明且可见的导航栏；不改变顶部状态栏、方向与键盘处理 |
| react-native-vision-camera-barcode-scanner 5.2.3 | 首次创建图片扫码器或相机扫码输出时初始化 ML Kit；宿主 Manifest 移除自动初始化 Provider，R8 保留反射注册器的无参构造 |

Compose React 根容器不添加 IME padding，键盘由宿主 KeyboardProvider 统一避让。页面选项不得重复接管全局导航栏策略。
