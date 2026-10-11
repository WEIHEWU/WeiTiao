# 资源与体积极致优化：保持 100% 可读性，不做混淆，保留行号与反射元数据
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Xposed / LSPosed 模块入口与框架依赖完整保留
-keep class com.weitiao.tuning.MainHook { *; }
-keep class io.github.libxposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**

# 反射访问与项目核心类完整保留
-keep class com.weitiao.tuning.** { *; }

# 忽略不影响编译运行的警告
-dontwarn **
