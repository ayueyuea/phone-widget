# 保留 Widget 组件
-keep class com.phonewidget.** { *; }

# 保留 Kotlin 相关类
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# 保留 AndroidX 组件
-keep class androidx.** { *; }
-dontwarn androidx.**

# 保留 Widget 相关的系统类
-keep class * extends android.appwidget.AppWidgetProvider {
    public *;
}

# 保留 BroadcastReceiver 相关方法
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}

# 保留日志相关类（如果使用Timber）
-keep class timber.log.** { *; }
-keep class org.jetbrains.annotations.** { *; }

# 避免混淆枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留数据类的方法
-keepclassmembers class * implements kotlin.jvm.internal.KObject {
    public <methods>;
}

# 保留 Parcelable 实现
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
