# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Coroutines
-keep class kotlinx.coroutines.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Google Play Billing
-keep class com.android.billingclient.** { *; }

# Google Play Integrity
-keep class com.google.android.play.core.** { *; }

# AndroidX Security
-keep class androidx.security.crypto.** { *; }

# Gson serialization
-keep class com.google.gson.** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Model classes used with Gson
-keep class com.example.rutaalmacen.entrada.ocr.ProductoLocal { *; }
-keep class com.example.rutaalmacen.entrada.ocr.ProductoEscaneado { *; }
-keep class com.example.rutaalmacen.notas.Nota { *; }
-keep class com.example.rutaalmacen.pagos.Plan { *; }
-keep class com.example.rutaalmacen.pagos.DetalleProductoSuscripcion { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
