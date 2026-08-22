-keep class io.github.sagernet.libbox.** { *; }
-keep class go.** { *; }
-keep class com.jhopanstore.litevpn.VpnService { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
