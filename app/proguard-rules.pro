-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# Vendored native helper — keep names; native code binds by class/method descriptor
-keep class io.github.vvb2060.callrecording.xposed.DexHelper { *; }

# WorkManager instantiates workers reflectively by class name — keep the constructor.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
# WorkManager bundles a Room database that Room loads reflectively via
# canonicalName + "_Impl"; R8 must not rename/strip these or startup crashes with
# "Failed to create an instance of class androidx.work.impl.WorkDatabase".
-keep class androidx.work.impl.WorkDatabase* { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.model.** { *; }
-dontwarn androidx.work.**
