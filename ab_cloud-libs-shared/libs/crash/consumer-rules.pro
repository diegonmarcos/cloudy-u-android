# #1474 SafeEncryptedPrefs opens security-crypto (and its tink) reflectively through Keystore; R8 must not strip it
# from an app that minifies.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# tink's optional Google HTTP client / Joda-Time references (KeysDownloader, Hmac): not used by EncryptedSharedPreferences.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
