# release 开启 R8（isMinifyEnabled / isShrinkResources 在 app/build.gradle.kts 的 release 块里）。
# 没开 minify 时这个文件不起作用；开了之后这就是"哪些东西不许裁"的清单。

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# kotlinx.serialization：@Serializable 的 serializer 靠伴生对象与反射名查找，被裁掉就会运行时炸。
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }

# ⚠️ 上游这里还有两条针对 androidx.security:security-crypto / Tink 的 keep 规则。
# 本原型不保存任何凭据，那个依赖已经从构建脚本移除，所以规则也一并删了 ——
# 留着只会让 R8 去保留一个根本不存在的包。