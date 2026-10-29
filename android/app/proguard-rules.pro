# App-specific R8/ProGuard rules.
# kotlinx.serialization ships its own consumer rules; add rules here only when a
# release build actually needs them (verified via :app:assembleRelease + smoke test).

# The packaged Mermaid document calls these names through WebView's JS bridge.
-keepclassmembers class app.hapi.companion.ui.markdown.MermaidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# MacroInfo resolves NewCommandMacro and executeMacro from runtime strings.
# Built-in macros such as operatorname use this dispatcher too.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}

# The packaged PDF.js viewer reports readiness/location and requests original download.
-keepclassmembers class app.hapi.companion.feature.files.PdfViewerBridge {
    @android.webkit.JavascriptInterface <methods>;
}
