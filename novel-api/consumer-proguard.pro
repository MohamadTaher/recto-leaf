# Extensions compile against this module but load against the host's copy, so R8 must not strip
# anything they may call, even when the app itself never does.
-keep class leaf.novel.api.** { public protected *; }
