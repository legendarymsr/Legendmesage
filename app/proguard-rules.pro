# Project-specific R8/ProGuard rules.

# TorClientAuth reaches jtorctl's protected sendAndWaitForResponse() and the
# ReplyLine.msg field by reflection. Keep them unrenamed or client auth breaks
# (and the app would fall back to an unauthenticated onion).
-keep class net.freehaven.tor.control.** { *; }
-keep class org.torproject.jni.** { *; }

# libsignal uses JNI callbacks into these classes.
-keep class org.signal.libsignal.** { *; }

# SQLCipher native bindings.
-keep class net.zetetic.database.** { *; }
