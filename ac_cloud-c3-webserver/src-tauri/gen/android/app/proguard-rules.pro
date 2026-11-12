# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# JNI: KeepAliveService.nativeStart is bound by name to the Rust library (src-tauri/src/lib.rs).
-keepclasseswithmembernames class com.diegonmarcos.cloudwebserver.KeepAliveService { native <methods>; }
# #1682 P1 the native MainActivity is bound to the Rust core both ways: nativeAttach / nativeDetach by name, and the core calls
# syncKeepAlive() on it by name (core/src/bridge.rs sync_keep_alive). R8 must keep both.
-keepclasseswithmembernames class com.diegonmarcos.cloudwebserver.MainActivity { native <methods>; }
-keepclassmembers class com.diegonmarcos.cloudwebserver.MainActivity { public void syncKeepAlive(); }
# Core.nativeUiSections is bound by class and method name (Java_com_diegonmarcos_cloudwebserver_Core_nativeUiSections).
-keepclasseswithmembernames class com.diegonmarcos.cloudwebserver.Core { native <methods>; }
