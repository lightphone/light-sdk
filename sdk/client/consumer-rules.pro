-keep class com.thelightphone.sdk.generated.LightSdkRegistry { *; }
-keep class com.thelightphone.sdk.generated.** { *; }

-keep @com.thelightphone.sdk.InitialScreen class * { *; }
-keep class * implements com.thelightphone.sdk.LightEntryPoint { *; }

-keep class com.thelightphone.sdk.LightFileProvider { *; }
-keep class com.thelightphone.sdk.LightPushService { *; }
-keep class com.thelightphone.sdk.LightActivity { *; }
-keep class com.thelightphone.sdk.SealedLightActivity { *; }
-keep class com.thelightphone.sdk.LightSdkApplication { *; }
-keep class com.thelightphone.sdk.LightSdkReceiver { *; }
