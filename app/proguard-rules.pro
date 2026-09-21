# ===== HyperBrightnessCurve R8 规则（release 已开 isMinifyEnabled / isShrinkResources）=====
#
# 关键前提：`io.github.libxposed:api` 是 compileOnly，运行期由 LSPosed 提供，
# R8 看不到这些类型 → 既不能靠「implements 匹配」写 keep（匹配不上），
# 又必须屏蔽 missing-class 警告（否则 R8 直接失败）。
# 因此策略是：按包名精确保住「框架会按名字反射/调用」的类，其余全部交给 R8。

# --- 1. 模块入口：META-INF/xposed/java_init.list 里写的类名必须与 dex 中一致 ---
-keep class com.blc.hypercurve.hook.HyperCurveEntry {
    <init>();
    public protected *;
}

# --- 2. 钩子实现：hook 包整体保住成员名 ---
# BrightnessHooks 里的 `object : XposedInterface.Hooker`（匿名类名 BrightnessHooks$withHooker$1）
# 与 onSlider / onCurrentNit / onWatch 都在这个包内；LSPosed 侧按 Hooker.intercept 的名字调用它们，
# 一旦被重命名就是 AbstractMethodError。保留整包只多几 KB。
-keep class com.blc.hypercurve.hook.** { *; }

# --- 3. libxposed consumer 规则（service aar 也带，这里显式写出便于阅读）---
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}

# --- 4. compileOnly 类型缺失：只静默，不影响正确性 ---
-dontwarn io.github.libxposed.**
-dontwarn io.github.libxposed.annotation.**
-dontwarn org.jetbrains.annotations.**

# --- 5. 若将来允许混淆入口类：同步改写清单里的类名（当前 hook 包不混淆，留着无害）---
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keepattributes *Annotation*,InnerClasses,Signature,RuntimeVisibleAnnotations,AnnotationDefault,Exceptions,MethodParameters

# Compose / Material3 不需要额外规则：各 AAR 自带 consumer 规则，界面代码无反射、无按名调用。
# UI 组件（MainActivity）由 AGP 依据 AndroidManifest 自动生成 keep。
