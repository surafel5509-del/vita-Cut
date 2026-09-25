# Vita Cut — app module ProGuard rules.
# Keep kotlinx.serialization metadata for the project document format (reflective fallback).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Media3 reflection entry points (effects, decoders).
-keep class androidx.media3.** { *; }

# ML Kit model classes are loaded reflectively.
-keep class com.google.mlkit.** { *; }
