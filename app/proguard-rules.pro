# Settings and layouts are read and written with kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.racewall.android.state.** {
    *** Companion;
}
-keepclasseswithmembers class com.racewall.android.state.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.racewall.android.state.**$$serializer { *; }

# WorkManager (home-screen widget refresh) makes its Room database by reflection at app start.
# Without this, R8 removes WorkDatabase_Impl's constructor and the app closes on open (0.1.6).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# OkHttp names optional platform classes it does not need on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
