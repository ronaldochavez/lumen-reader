# Minification is disabled for now (see app/build.gradle.kts).
# If you enable R8, keep the Anthropic SDK + Jackson models:
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn io.swagger.**
-dontwarn com.github.victools.**
