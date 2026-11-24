# JNI: the native glue finds these by name.
-keep class com.paintbymoonlight.core.NativeCore { native <methods>; }
-keep class com.paintbymoonlight.core.CoreException { <init>(int); }
