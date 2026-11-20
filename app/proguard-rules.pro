# Shizuku starts this UserService by its class name in a process of its own.
-keep class dev.ririxi.bunnylock.shizuku.ShizukuService { *; }

# Mapsforge (SignalX 1.0.9 rules): the render theme is loaded by
# reflection (MapsforgeThemes is an internal package) and the map reader
# uses reflection too, so R8 must not strip or rename them.
-keep class org.mapsforge.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-keeppackagenames org.mapsforge.**, com.caverock.**
