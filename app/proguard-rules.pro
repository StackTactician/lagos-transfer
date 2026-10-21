# The app talks to the game server with plain JSON, nothing reflective is needed.
# Keep the model classes readable in crash logs.
-keep class com.lagostransfer.data.** { *; }
-keepattributes SourceFile,LineNumberTable

# Tink (used by androidx.security-crypto) mentions error prone annotations that
# are not on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.**
