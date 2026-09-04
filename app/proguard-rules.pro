-dontwarn io.github.libxposed.annotation.**
# libxposed service 库引用了 androidx 注解（仅编译期），本项目不引入 androidx，运行时无影响
-dontwarn androidx.annotation.NonNull
-dontwarn androidx.annotation.Nullable
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
