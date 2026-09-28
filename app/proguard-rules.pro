# BouncyCastle is bundled for Apple's CMS/PKCS#12 work, and the platform
# provider omits those algorithms.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# kotlinx.coroutines is used by the protocol layer.
-dontwarn kotlinx.coroutines.**

# Keep the certificate and key material types used by the pairing record.
-keep class me.androidloader.pairing.** { *; }
-keep class me.androidloader.lockdown.** { *; }

# Coroutine internals referenced reflectively by the debugger support.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
