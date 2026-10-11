# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# Room instantiates generated databases by name and a no-argument constructor.
-keep class * extends androidx.room.RoomDatabase { *; }
# Scheduled workers can outlive the APK that originally saved their class names.
-keep class com.zijinhua.app.bundle.ReleaseCheckWorker { *; }

# ML Kit discovers these implementations from manifest metadata via reflection.
# R8 full mode must retain the public no-argument constructors too.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# SplitBundle invokes RN's internal loader reflectively (Kotlin adds a module suffix).
-keepclassmembers class com.facebook.react.runtime.ReactHostImpl {
    *** loadBundle*(com.facebook.react.bridge.JSBundleLoader);
}

# PDF page extraction copies encoded image streams without decoding JPEG 2000.
# PDFBox references this optional codec only from its image decode/encode filters.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
