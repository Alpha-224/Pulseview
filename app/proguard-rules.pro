# ONNX Runtime JNI rules
-keep class ai.onnxruntime.** { *; }

# OpenCV JNI rules
-keep class org.opencv.** { *; }

# Firebase Firestore Serialization rules
-keep class com.Twinhealth.pulseview.HrReading { *; }
-keepclassmembers class com.Twinhealth.pulseview.HrReading {
    <fields>;
    <init>(...);
}
