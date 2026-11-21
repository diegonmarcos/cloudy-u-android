# #1842 the Url-Uri dropdown reads the host's baked nav declaration from its BuildConfig by name (UrlUriBuildConfig).
-keepclassmembers class **.BuildConfig { public static final java.lang.String UI_*; }
