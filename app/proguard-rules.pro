# The accessibility service is instantiated by the platform from the name in the manifest,
# so R8 has no reference to it and would otherwise be free to rename or remove it.
-keep class io.github.maztergd.interruptor.service.DoomscrollAccessibilityService { *; }

# Overlay views are constructed reflectively by the view inflation machinery on some paths.
-keep class io.github.maztergd.interruptor.service.overlay.** { <init>(...); }

# Kotlin metadata is required for reflection-free coroutines debugging to stay useful and
# for DataStore's generated accessors.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep the line numbers in any crash a user chooses to report by hand; without an uploaded
# mapping file (this project publishes none) an obfuscated trace would be useless.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
