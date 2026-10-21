# The app talks to the game server with plain JSON, nothing reflective is needed.
# Keep the model classes readable in crash logs.
-keep class com.lagostransfer.data.** { *; }
-keepattributes SourceFile,LineNumberTable
