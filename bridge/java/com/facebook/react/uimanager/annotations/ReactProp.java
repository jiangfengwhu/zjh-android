package com.facebook.react.uimanager.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only stub matching RN 0.87 ReactProp members. Do not dex this file into a plugin. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ReactProp {
  String USE_DEFAULT_TYPE = "__default_type__";

  String name();

  String customType() default USE_DEFAULT_TYPE;

  double defaultDouble() default 0.0;

  float defaultFloat() default 0.0f;

  int defaultInt() default 0;

  long defaultLong() default 0L;

  boolean defaultBoolean() default false;
}
