# The separate androidTest APK calls VectorHarness by name at runtime, so R8 must keep it.
# The real SDK verifier code called by VectorHarness is left shrinkable on
# purpose, so this test actually proves that code still works after minification.
-keep class com.example.lighttrustvalidation.VectorHarness { public static *; }
