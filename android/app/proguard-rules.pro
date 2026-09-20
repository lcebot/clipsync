-dontwarn io.github.libxposed.annotation.**
-dontwarn de.robv.android.xposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Stack traces have to survive minification, because Logger writes them into files/clipsync.log and
# that log is the whole of what a user can send back when something goes wrong. Without these two,
# a release build's traces are "Unknown Source" all the way down and a report is unactionable.
#
# -renamesourcefileattribute replaces the real file names with a single constant, so keeping
# SourceFile does not leak the source tree into the APK: line numbers still map back through the
# mapping file, which is what retrace needs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The libxposed entry point, kept whole (name preserved so it keeps matching the plain FQN in
# META-INF/xposed/java_init.list).
#
# libxposed API 102 instantiates the module via a PUBLIC NO-ARG constructor and then calls
# XposedInterfaceWrapper.attachFramework(XposedInterface); it does NOT use a
# (XposedInterface, ModuleLoadedParam) constructor. (The parameterized-constructor shape belonged to
# an earlier pre-release API and was dropped; see the api's XposedModule "Constructor Requirements":
# public default constructor only, framework attaches the interface afterwards, and no work may be
# done before onModuleLoaded().) Entry declares no constructor, so it gets the implicit public no-arg
# one; keeping the class whole keeps that constructor and the overridden lifecycle callbacks the
# loader invokes reflectively.
#
# The library's own recommended rule is `-keep,allowobfuscation class * extends XposedModule` +
# `-keepclassmembers { public <init>(); }` and relies on `-adaptresourcefilecontents` to rewrite the
# obfuscated name into java_init.list. We deliberately keep the name un-obfuscated instead: simpler,
# and it can never desync from java_init.list. Entry is the ONLY class listed there, so a generic
# `extends XposedModule` keep rule would be redundant and has been removed.
-keep class io.github.lcebot.clipsync.xposed.Entry { *; }
