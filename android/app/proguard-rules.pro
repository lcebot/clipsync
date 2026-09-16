-dontwarn io.github.libxposed.annotation.**
-dontwarn de.robv.android.xposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
# classic API entry is located by name (assets/xposed_init)
-keep public class io.github.lcebot.clipsync.xposed.LegacyEntry {
    public <init>();
}
