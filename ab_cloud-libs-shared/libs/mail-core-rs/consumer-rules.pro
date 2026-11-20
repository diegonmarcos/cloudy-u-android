# libs:mail-core-rs (#1837): libmail_core.so binds NativeBridge's natives by name, and calls MailCoreTransport.post(String) on the
# app's transport object by name. R8 must neither rename nor strip them.
-keep class com.diegonmarcos.cloudlib.mailcore.NativeBridge { *; }
-keep interface com.diegonmarcos.cloudlib.mailcore.MailCoreTransport { *; }
-keep class * implements com.diegonmarcos.cloudlib.mailcore.MailCoreTransport { public java.lang.String post(java.lang.String); }
