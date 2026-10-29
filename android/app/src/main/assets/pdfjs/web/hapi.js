/* HAPI integration for the packaged Mozilla PDF.js viewer. */
document.addEventListener("webviewerloaded", () => {
  const app = window.PDFViewerApplication;
  const options = window.PDFViewerApplicationOptions;
  options.set("disablePreferences", true);
  options.set("enableScripting", false);
  options.set("annotationEditorMode", -1);
  options.set("enableComment", false);
  options.set("enableAltTextModelDownload", false);
  options.set("enableSignatureEditor", false);
  options.set("enableSplitMerge", false);
  options.set("externalLinkTarget", 0);
  app.initializedPromise.then(() => {
    // Save the original document through Android's document picker, including
    // the keyboard shortcut, rather than dispatching a WebView blob download.
    app.download = app.downloadOrSave = app.save = async () => window.HapiPdf.onDownload();
    app.eventBus.on("pagerendered", () => window.HapiPdf.onReady());
    app.eventBus.on("documenterror", () => window.HapiPdf.onError());
    app.eventBus.on("updateviewarea", event => window.HapiPdf.onViewState(event.location.pdfOpenParams));
  }).catch(() => window.HapiPdf.onError());
});
window.addEventListener("unhandledrejection", () => window.HapiPdf.onError());
window.addEventListener("error", () => window.HapiPdf.onError());
