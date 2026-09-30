package com.facebook.react.uimanager;

import android.view.View;

/**
 * Compile-only stub matching RN 0.87 SimpleViewManager method names.
 * The APK supplies the real class. Do not dex this file into a plugin.
 */
public abstract class SimpleViewManager<T extends View> {
  public SimpleViewManager() {}

  public abstract String getName();

  protected abstract T createViewInstance(ThemedReactContext reactContext);
}
