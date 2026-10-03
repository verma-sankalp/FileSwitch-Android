-keep class com.fileswitch.app.MainActivity { *; }
-keep class com.fileswitch.app.SharedFileProvider { *; }

-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.openxmlformats.**
-dontwarn org.apache.xmlbeans.**

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

-keep class androidx.exifinterface.** { *; }
-dontwarn androidx.exifinterface.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-dontwarn javax.xml.stream.**
-dontwarn javax.xml.namespace.**
-dontwarn java.awt.**
