# The Xposed API is provided by the framework at run time and is absent from the APK.
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# The framework loads the entry class by the name written in META-INF/xposed/java_init.list.
-keep class io.github.sfsmoon.animatuna.AnimatunaModule { *; }
-adaptresourcefilecontents META-INF/xposed/java_init.list
