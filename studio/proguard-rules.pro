# Lumen Studio: screens and services are referenced from the manifest; the JS bridge of the WebView layer by name.
-keep class org.schabi.newpipe.lumen.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.mozilla.javascript.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
