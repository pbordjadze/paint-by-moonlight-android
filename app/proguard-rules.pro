# JNI: the native glue finds these by name.
-keep class com.paintbymoonlight.core.NativeCore { native <methods>; }
-keep class com.paintbymoonlight.core.CoreException { <init>(int); }
# kotlinx.serialization, Google Pay and the AndroidX libraries ship their own rules, which AGP
# applies (build/outputs/mapping/release/configuration.txt lists them all).
