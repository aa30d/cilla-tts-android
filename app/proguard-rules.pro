# JNA reaches its native bindings through reflection and loads libptts_ffi.so by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# The TTS engine and the activity are named in the manifest; the generated view-binding classes
# and the model classes are reached from them.
-keep class com.cilla.tts.** { *; }
-dontwarn com.sun.jna.**
-dontwarn javax.**
-dontwarn java.lang.management.**
