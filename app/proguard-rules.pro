# Started by class name through app_process (adb); no Java call site for R8 to follow.
-keep class com.shashankanil.halo.core.HelperMain {
    public static void main(java.lang.String[]);
}
# libadb loads Conscrypt and BouncyCastle reflectively.
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class io.github.muntashirakon.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
