# Keep root / libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Compose
-keep class androidx.compose.** { *; }

# Application
-keep class com.hyperisland.root.** { *; }

# Xposed / LSPosed
-keep class com.hyperisland.root.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
