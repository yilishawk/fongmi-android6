# 来自影子工程产出的 AAR 根部的 proguard.txt（Chaquopy 插件自动生成的消费端规则）。
# 必须保留：Chaquopy 的 Cython 代码是**反射**调用这些 Java 类的，R8 看不到引用。
# 删掉会在 release（minifyEnabled）上炸。

# Ensure all classes and methods used by Cython code are left alone by minifyEnabled.
-keep class com.chaquo.python.** { * ; }

# See get_sam in class.pxi.
-keep class kotlin.jvm.functions.** { * ; }
-keep class kotlin.jvm.internal.FunctionBase { * ; }
-keep class kotlin.reflect.KAnnotatedElement { *; }

# TODO: https://github.com/chaquo/chaquopy/issues/842
-dontwarn org.jetbrains.annotations.NotNull
