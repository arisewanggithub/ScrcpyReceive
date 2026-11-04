-repackageclasses dev.scrcpy.releasecheck.internal
-keep class dev.scrcpy.releasecheck.ReleaseCheckRunner { public <init>(); }
-keep class dev.scrcpy.releasecheck.NativeOpusCheck {
    public <init>();
    @org.junit.Test public void *(...);
}
-keep class dev.scrcpy.releasecheck.TargetResourcesCheck {
    public <init>();
    @org.junit.Test public void *(...);
}
# Error Prone 2.41.0 IncompatibleModifiers is CLASS-retained annotation-type
# compiler metadata. Its deprecated value() references an unavailable JDK
# compiler enum; no runtime test uses that type and no compiler stubs are bundled.
-dontwarn javax.lang.model.element.Modifier
