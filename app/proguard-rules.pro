# Runtime libraries provide their own consumer rules. Preserve source locations
# so production crash reports remain actionable after R8 optimization.
-keepattributes SourceFile,LineNumberTable

# GeneratedMessageLite resolves the generated field names embedded in its
# message-info table at runtime. R8 may still obfuscate the message classes and
# methods, but these fields must retain their generated names.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
