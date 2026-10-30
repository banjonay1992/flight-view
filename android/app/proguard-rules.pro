# kotlinx.serialization: keep generated serializers for API and Supabase models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.nayproductions.flightview.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.nayproductions.flightview.**$$serializer { *; }
-dontwarn org.slf4j.**
