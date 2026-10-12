# Keep the JavaScript interface for WebView
-keepclassmembers class com.kmibrahim.deenorav3.MainActivity$WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# General Android rules
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature

# Keep every JS bridge method (GoogleSignInBridge, WebAppInterface)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}