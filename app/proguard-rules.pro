# ===== R8 规则（release 已开 isMinifyEnabled / isShrinkResources）=====
#
# `io.github.libxposed:api` 是 compileOnly，R8 看不到这些类型：
# 因此按包名精确保住「框架按名字反射/调用」的类，并对缺失类型屏蔽警告。

# --- 1. 模块入口：META-INF/xposed/java_init.list 里写的类名必须与 dex 中一致 ---
-keep class com.blc.hypercurve.hook.HyperCurveEntry {
    <init>();
    public protected *;
}

# --- 2. 钩子实现：hook 包整体保住成员名 ---
# 包内的 `object : XposedInterface.Hooker` 与各钩子方法都由 LSPosed 按名字调用，
# 重命名会导致 AbstractMethodError；整包保留只有几 KB。
-keep class com.blc.hypercurve.hook.** { *; }

# --- 3. libxposed consumer 规则（service aar 也带，这里显式写出）---
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}

# --- 4. compileOnly 类型缺失：对 R8 静默 ---
-dontwarn io.github.libxposed.**
-dontwarn io.github.libxposed.annotation.**
-dontwarn org.jetbrains.annotations.**

# --- 5. 若 hook 包将来参与混淆：同步改写清单里的类名 ---
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keepattributes *Annotation*,InnerClasses,Signature,RuntimeVisibleAnnotations,AnnotationDefault,Exceptions,MethodParameters

# Compose / Material3 由各 AAR 自带的 consumer 规则覆盖；界面代码无反射、无按名调用。
# UI 组件（MainActivity）由 AGP 依据 AndroidManifest 自动生成 keep。
