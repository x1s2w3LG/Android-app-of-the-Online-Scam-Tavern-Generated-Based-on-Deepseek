# ===========================================================================
#  骗子酒馆 Android 壳 —— ProGuard / R8 规则
#
#  注意：当前 build.gradle 里 release 也是 minifyEnabled false，
#  这套规则是「以后想开混淆时」的保险，现在不生效也无害。
# ===========================================================================

# --- core 模块：游戏服务端 + 协议模型，绝对不能改名/裁剪 ----------------
-keep class com.liarsbar.core.** { *; }
-keep interface com.liarsbar.core.** { *; }
-keepclassmembers class com.liarsbar.core.** { *; }

# --- Android 壳自身（反射调用 JS 接口、Service 生命周期）----------------
-keep class com.liarsbar.android.** { *; }

# --- WebView 的 @JavascriptInterface 桥接方法不能被混淆 ------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- 活动/服务/接收器（AGP 会自动加，这里显式写一遍更保险）--------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application

# --- JSON 序列化（core 若用反射式 JSON 库，保留字段名）------------------
-keepclassmembers class * {
    public <init>(...);
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
