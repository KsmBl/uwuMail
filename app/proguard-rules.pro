# ---------------------------------------------------------------- JavaMail
# JavaMail resolves providers and content handlers by name at runtime, from
# META-INF/javamail.* and from the mailcap entries registered in UwuMailApp.
# R8 cannot see any of those references, so the classes must be kept whole.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }

# Handlers named only as strings in the MailcapCommandMap registration.
-keep class com.sun.mail.handlers.** { *; }

# JavaMail is compiled against a full JRE; these are absent on Android and
# unreachable at runtime.
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**
-dontwarn org.ietf.jgss.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ------------------------------------------------------------------- jsoup
-dontwarn org.jsoup.**

# -------------------------------------------------------------------- Room
# Entities are referenced by generated code, but keep their members so column
# names survive: Room maps by field name.
-keep class de.uwumail.data.db.** { *; }

# Kotlin metadata used by reflection in a few AndroidX components.
-keep class kotlin.Metadata { *; }
