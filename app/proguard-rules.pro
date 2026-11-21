# Add project specific ProGuard / R8 rules here.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep Firebase Firestore reflection-deserialized models
-keep class com.example.data.FarmerCloudWorkspace { *; }
-keep class com.example.data.CloudChatLog { *; }

# Keep Room SQLite entities and DAOs
-keep class com.example.data.SqlFieldAuditEntity { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

# Keep RBAC & domain enums used in serialization
-keepclassmembers enum com.example.model.** { *; }
-keepclassmembers enum com.example.data.** { *; }

# Keep ViewModels and their Application constructors for ViewModelProvider reflection
-keep class com.example.viewmodel.** { *; }
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    public <init>(android.app.Application);
}

# Silence benign warnings from OkHttp / Conscrypt / Kotlin Coroutines
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

