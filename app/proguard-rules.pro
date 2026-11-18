-dontobfuscate
-dontoptimize
-keepattributes SourceFile,LineNumberTable,Exceptions

# Anything less causes issues like not finding primary constructor in ReflectionDiffer
-keep class org.fdroid.** {*;}

# Logging
-keep class ch.qos.logback.classic.android.LogcatAppender
-keepclassmembers class ch.qos.logback.** { *; }
-keepclassmembers class  org.slf4j.impl.** { *; }

# Needed for instrumentation tests (for some werid inexplicable reason)
-keep class kotlin.LazyKt
-keep class kotlin.collections.CollectionsKt

# Used for full's nearby feature
-keep class kellinwood.security.zipsigner.** {*;}
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }

# for debugging (comment in when needed)
#-printconfiguration build/outputs/logs/r8-configuration.txt

# Install methods (Shizuku, Dhizuku, microG, root, privileged extension):
# these libraries talk to other processes via binder and refer to hidden framework classes,
# so keep them as they are and don't fail the release build over classes that only exist on a device.
-keep class rikka.shizuku.** { *; }
-keep class com.rosan.dhizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn com.rosan.dhizuku.**
-dontwarn org.lsposed.hiddenapibypass.**
-dontwarn android.content.pm.IPackageInstaller**
-dontwarn android.content.pm.IPackageManager**
