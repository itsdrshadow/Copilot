# R8 rules for Copilot. Room, Hilt, WorkManager, MapLibre, OkHttp and Play Services ship their
# own consumer rules; only what this app does by reflection is listed here.

# Keep line numbers in crash stack traces (retrace with mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Gson (2.10.x ships no R8 rules) ---
# Gson reads/writes fields by name: backups (FullBackupManager) serialize Room entities, the fuel
# model state is stored as JSON in the DB. Renaming a field would break every existing backup and
# the saved fuel model, so field names in those packages must survive.
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }

-keepclassmembers class com.antigravity.copilot.core.database.** { <fields>; <init>(...); }
-keepclassmembers class com.antigravity.copilot.core.fuel.station.** { <fields>; <init>(...); }
-keepclassmembers class com.antigravity.copilot.core.road.** { <fields>; <init>(...); }
-keepclassmembers class com.antigravity.copilot.core.logic.fuel.** { <fields>; <init>(...); }
-keepclassmembers class com.antigravity.copilot.core.persistence.** { <fields>; <init>(...); }

# Gson's enum adapter looks constants up with Class.getField(name()).
-keepclassmembers enum com.antigravity.copilot.** { <fields>; }

# MapDownloadWorker is created by WorkManager's default factory by class name.
-keep class com.antigravity.copilot.core.map.MapDownloadWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

-dontwarn org.slf4j.**
