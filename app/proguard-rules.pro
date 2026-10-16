# Settings and layouts are read and written with kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.racewall.android.state.** {
    *** Companion;
}
-keepclasseswithmembers class com.racewall.android.state.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.racewall.android.state.**$$serializer { *; }

# OkHttp names optional platform classes it does not need on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
