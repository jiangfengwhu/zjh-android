# Jolly video rendering

`react-native-video` 6.19.3 constructs Android's `PlayerView` without attributes,
which selects a `SurfaceView`. Its `updateSurfaceView` method is empty, so the
`ViewType.TEXTURE` prop in `zjh-dynamic/packages/ui/jolly/video.tsx` does not change that surface.

The patch inflates a `PlayerView` with `surface_type="texture_view"` and preserves
the last frame when the player resets. All current app video usage is Jolly's
inline, unencrypted animation, which needs scrolling, transforms and rounded
clipping. This patch selects texture rendering for every Android video instance;
revisit it before adding secure/DRM video or upgrading the video library.

`npm install` / `npm ci` applies it through `postinstall`. Native changes require
a new APK; publishing a JavaScript bundle alone cannot apply this fix.
