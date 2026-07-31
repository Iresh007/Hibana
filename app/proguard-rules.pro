# Keep source/extension entry points loaded reflectively by the extension loaders.
-keep class com.opennovel.reader.source.** { *; }
-keep class com.opennovel.reader.extension.** { *; }
-keepclassmembers class * implements com.opennovel.reader.source.Source { *; }
