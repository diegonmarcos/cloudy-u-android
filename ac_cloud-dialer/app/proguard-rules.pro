# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Cloud Dialer: commons' repackaging check ("you are using a fake version of the app ... get the
# original") is compiled into commons' Compose theme (AppTheme -> FakeVersionCheck -> fakeVersionCheck)
# and into the sideloading helpers, so every commons dialog could raise it on a fork under its own
# package name. This app is an intentional fork: the checks are cut out of the release build.
# test/test-no-fake-app-check.sh fails if these rules, or any call into the check, go missing.
-assumenosideeffects class org.fossify.commons.compose.extensions.ActivityExtensionsKt {
    void fakeVersionCheck(android.content.Context, kotlin.jvm.functions.Function0);
}
-assumenosideeffects class org.fossify.commons.extensions.ActivityKt {
    void showSideloadingDialog(android.app.Activity);
}
-assumevalues class org.fossify.commons.extensions.ActivityKt {
    boolean checkAppSideloading(android.app.Activity) return false;
    boolean isAppSideloaded(android.app.Activity) return false;
}

# #1415 security-crypto (EncryptedSharedPreferences / MasterKey via libs:crash SafeEncryptedPrefs): sha-61326f78 crashed
# with NoClassDefFoundError MasterKey$Builder. The library is linked in app/build.gradle.kts; keep it whole, since it is
# reached through libs:crash and Tink's key managers are looked up reflectively. Tink's compile-time annotations are absent.
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
