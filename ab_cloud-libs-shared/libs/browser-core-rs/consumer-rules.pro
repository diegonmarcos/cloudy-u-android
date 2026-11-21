# libs:browser-core-rs (#1841): libbrowser_core.so binds NativeBridge's natives by name. R8 must neither rename nor strip them.
-keep class com.diegonmarcos.cloudlib.browsercore.NativeBridge { *; }
