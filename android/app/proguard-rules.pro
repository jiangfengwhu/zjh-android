# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# Public bridge names are also referenced by independently compiled DEX plugins.
-keep class com.zijinhua.app.plugin.BridgeHost { *; }
-keep interface com.zijinhua.app.plugin.BridgePlugin { *; }
-keep interface com.zijinhua.app.plugin.PluginHandler { *; }
-keep class com.zijinhua.app.plugin.PluginRegistry { *; }
-keep class com.facebook.react.uimanager.SimpleViewManager { *; }
-keep class com.facebook.react.uimanager.ThemedReactContext { *; }
-keep @interface com.facebook.react.uimanager.annotations.ReactProp { *; }

# Room instantiates generated databases by name and a no-argument constructor.
-keep class * extends androidx.room.RoomDatabase { *; }
# Scheduled workers can outlive the APK that originally saved their class names.
-keep class com.zijinhua.app.bundle.ReleaseCheckWorker { *; }

# SplitBundle invokes RN's internal loader reflectively (Kotlin adds a module suffix).
-keepclassmembers class com.facebook.react.runtime.ReactHostImpl {
    *** loadBundle*(com.facebook.react.bridge.JSBundleLoader);
}
