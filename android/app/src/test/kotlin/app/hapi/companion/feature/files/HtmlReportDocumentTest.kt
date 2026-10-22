package app.hapi.companion.feature.files

import org.junit.Test
import org.junit.Assert.*
import java.net.URI

class HtmlReportDocumentTest {
    @Test fun mapsAbsoluteReportAndSiblingResourcesToTheRemoteMachine() {
        val report = HtmlReportDocument("/Share/mingfei/my report.html", "<!doctype html><meta charset=utf-8><h1>Report</h1>")
        assertEquals("/Share/mingfei/my report.html", report.resourcePath(report.url))
        assertEquals("/Share/mingfei/curve.png", report.resourcePath(URI(report.url).resolve("curve.png?v=1").toString()))
        assertEquals("/Share/other.css", report.resourcePath(URI(report.url).resolve("../other.css").toString()))
        assertTrue(report.content.contains("name=\"viewport\""))
    }
    @Test fun relativeReportsKeepResourcesRelativeToWorkspaceAndSupportWindows() {
        val relative = HtmlReportDocument("reports/index.html", "<html><head></head><body>中文</body></html>")
        assertEquals("reports/plot.svg", relative.resourcePath(URI(relative.url).resolve("plot.svg").toString()))
        val windows = HtmlReportDocument("C:\\work\\report.html", "Report")
        assertEquals("C:/work/image.png", windows.resourcePath(URI(windows.url).resolve("image.png").toString()))
    }
    @Test fun neverMapsHubOrExternalOriginsIntoFileReadsAndKeepsExistingViewport() {
        val html = "<head><meta name='viewport' content='width=1200'></head>"
        val report = HtmlReportDocument("report.html", html)
        assertEquals(html, report.content)
        for (url in listOf("https://hapi.gaomf.store/api/sessions", "file:///etc/hosts", "https://hapi-report.invalid.evil/a", "https://hapi-report.invalid:443/a", "https://hapi-report.invalid/%QQ")) {
            assertNull(report.resourcePath(url))
        }
    }
}
