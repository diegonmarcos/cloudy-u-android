# #1474 SafeEncryptedPrefs opens security-crypto (and its tink) reflectively through Keystore; R8 must not strip it
# from an app that minifies.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
