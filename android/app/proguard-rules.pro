-dontwarn de.robv.android.xposed.**
# classic API entry is located by name (assets/xposed_init)
-keep public class io.github.lcebot.clipsync.xposed.LegacyEntry {
    public <init>();
}
