# #1949 the pdfium binding reaches its JNI half by name: R8 in a minifying consumer (Cloud Notes, Cloud Camera) must not rename it.
-keep class io.legere.pdfiumandroid.** { *; }
-dontwarn io.legere.pdfiumandroid.**
