# JavaMail / Angus
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn java.awt.**
-keepattributes *Annotation*,Signature,InnerClasses
