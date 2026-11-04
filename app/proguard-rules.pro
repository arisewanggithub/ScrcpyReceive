# C++ exports JNI symbols by declaring class and method name. Root the bindings,
# not their entire packages; non-native members remain shrinkable.
-keep,allowoptimization class org.screencopy.receiver.media.OpusDecoder {
    public <init>(int, int);
    native <methods>;
}

# AGP generates manifest-component rules for MainActivity and UpdateFileProvider.
# Updater JSON is parsed explicitly, so no model-field or whole updater-package
# keep is needed.
