# Shrink and optimise, but keep names: a personal app gains nothing from obfuscation,
# and readable release stack traces are worth more than the few kilobytes.
-dontobfuscate

# The glasses SDK. Only its core module ships consumer rules; the camera module ships
# none, and it reaches its own classes and protocol messages reflectively.
-keep class com.meta.wearable.dat.** { *; }
-keep class com.meta.media.stream.proto.** { *; }
-keep class com.facebook.wearable.** { *; }

# Protobuf lite reads message fields by reflection.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
