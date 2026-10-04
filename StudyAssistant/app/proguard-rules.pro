
# ---- Study Assistant release rules ----------------------------------------------------------
# pdfbox-android references optional JPEG 2000 support that is not bundled.
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# pdfbox loads fonts and resources by class name.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
# Credential Manager reaches the Play Services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
