# =============================================================================
# ProGuard / R8 keep rules for Aria (release builds run with isMinifyEnabled).
# =============================================================================

# -----------------------------------------------------------------------------
# androidx.security:security-crypto + Google Tink (EncryptedSharedPreferences)
# -----------------------------------------------------------------------------
# security-crypto (see security/SecurePrefs.kt) stores the SIP password, JWT and
# gateway token in an EncryptedSharedPreferences file. It is backed by Google
# Tink, which resolves its key managers / primitives REFLECTIVELY and parses
# protobuf-generated key types. If R8 strips or renames those classes, a
# minified release build throws at runtime the first time encrypted prefs are
# opened (typically a GeneralSecurityException / "No KeyManager found" /
# ClassNotFoundException), which would brick provisioning and sign-in.
#
# These are the well-known recommended keeps for Tink on Android.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }

# Tink pulls in the Protobuf-lite runtime for its key protos; keep the generated
# message types and their reflective accessors intact.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Tink references a few error-prone / j2objc annotations at compile time that are
# not present on the Android classpath. Don't warn/fail on the missing symbols.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.errorprone.annotations.concurrent.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# -----------------------------------------------------------------------------
# JNA (transitively used by some Tink / native-backed integrations)
# -----------------------------------------------------------------------------
# JNA maps Java interfaces onto native symbols reflectively; its Structure/
# Callback subclasses and native-method-bearing classes must survive R8.
# These keeps are inert if JNA is not actually on the classpath.
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
}
-dontwarn com.sun.jna.**
