# libsignal / Signal protocol (consumer rules + JNI)
-keep class org.signal.** { *; }
-keep class org.libsignal.** { *; }
-keepclassmembers class * {
    native <methods>;
}
-dontwarn org.signal.**
-dontwarn org.libsignal.**

# SQLCipher native
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Tink / security-crypto annotation stubs (compile-time only)
-dontwarn com.google.errorprone.annotations.**

# Kotlin serialization (API models)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}