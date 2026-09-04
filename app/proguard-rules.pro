# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ─────────────────────────────────────────────────────────────────────────────
# SPIKE (spike/r8-and-streaming): keep-rules for the R8/minify feasibility test.
# Not merged — see the spike report. These are the rules R8 needs to not break
# the three reflection/JNI-sensitive surfaces in this app.
# ─────────────────────────────────────────────────────────────────────────────

# Readable crash traces from a minified release build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- JNI: native cracker (libwpacrack.so) ---
# crackBatch / crackBatchEapol are `external fun`; the .so exports symbols built
# from the exact class + method names (Java_com_wsvdmeer_..._crackBatch). If R8
# renames either, the native lookup fails with UnsatisfiedLinkError at runtime.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.wsvdmeer.pwncompanion.crack.NativeWpaCracker { *; }

# --- kotlinx.serialization ---
# @Serializable types resolve their generated $serializer reflectively via the
# Companion / INSTANCE.serializer(); R8 must not strip or rename those hooks.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$Companion Companion;
}
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-dontnote kotlinx.serialization.**

# --- Ktor (embedded CIO server + client) ---
# Ktor ships most consumer rules, but pulls in optional deps it references
# reflectively and that aren't on the Android classpath.
-dontwarn kotlinx.coroutines.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# --- WorkManager / Room (WorkDatabase) ---
# WorkManager auto-initialises at startup via androidx.startup and instantiates
# its Room-backed WorkDatabase reflectively: Room does Class.forName(dbClass
# canonicalName + "_Impl"). Under R8 full mode the database subclass gets renamed
# while the generated *_Impl keeps its original name, so the lookup fails with
# "Failed to create an instance of class androidx.work.impl.WorkDatabase" and the
# app crashes before the first frame. Keep WorkManager + every RoomDatabase (and
# its generated _Impl) un-renamed. (The app itself uses DataStore, not Room — the
# only Room database here is WorkManager's.)
-keep class androidx.work.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public <init>();
}
-dontwarn androidx.work.**