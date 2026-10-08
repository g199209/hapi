package app.hapi.companion

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercise the production hub observer with a real, saved NavController. */
class NavigationRestorationTest {
    @get:Rule val compose = createComposeRule()
    private val hub = mutableStateOf<String?>("https://hub-a.example")
    private lateinit var nav: NavHostController
    private val path = "src/文件 with spaces.kt"

    private fun mount(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            ObserveActiveHubNavigation(nav, hub.value)
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { Text("Home") }
                composable(Routes.PAIRING) { Text("Pairing") }
                composable(Routes.CHAT) { Text("Chat") }
                composable(Routes.FILES) { Text("Files") }
                composable(Routes.SCRATCHLIST) { Text("Scratchlist") }
                composable(Routes.FILE_VIEWER, arguments = listOf(
                    navArgument("sessionId") { type = NavType.StringType },
                    navArgument("path") { type = NavType.StringType },
                    navArgument("staged") { type = NavType.StringType; nullable = true },
                    navArgument("mode") { type = NavType.StringType; nullable = true },
                    navArgument("line") { type = NavType.StringType; nullable = true },
                )) { Text("Diff") }
            }
        }
        compose.waitForIdle()
        return restoration
    }

    private fun route(expected: String) = compose.runOnIdle { assertEquals(expected, nav.currentDestination?.route) }
    private fun navigate(destination: String) {
        compose.runOnIdle { nav.navigate(destination) }
        compose.waitForIdle()
    }

    @Test fun diffSurvivesRepeatedRestorationWithItsArgumentsAndBackStack() {
        val restoration = mount()
        navigate(Routes.chat("session-1"))
        navigate(Routes.files("session-1"))
        navigate(Routes.fileViewer("session-1", path, staged = true, mode = "diff", line = 42))
        repeat(2) {
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            route(Routes.FILE_VIEWER)
            compose.runOnIdle {
                val args = nav.currentBackStackEntry!!.arguments!!
                assertEquals("session-1", args.getString("sessionId"))
                assertEquals(path, Routes.decodeFilePath(args.getString("path")!!))
                assertEquals("true", args.getString("staged"))
                assertEquals("diff", args.getString("mode"))
                assertEquals("42", args.getString("line"))
            }
        }
        for (expected in listOf(Routes.FILES, Routes.CHAT, Routes.HOME)) {
            compose.runOnIdle { nav.popBackStack() }
            compose.waitForIdle()
            route(expected)
        }
    }

    @Test fun chatFilesAndScratchlistSurviveRestorationButRealHubSwitchAndSignOutStillReset() {
        val restoration = mount()
        for (destination in listOf(Routes.CHAT, Routes.FILES, Routes.SCRATCHLIST)) {
            navigate(destination.replace("{sessionId}", "session-1"))
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            route(destination)
            compose.runOnIdle { nav.popBackStack(Routes.HOME, false) }
            compose.waitForIdle()
        }
        navigate(Routes.chat("session-1"))
        navigate(Routes.files("session-1"))
        compose.runOnIdle { hub.value = "https://hub-b.example" }
        compose.waitForIdle()
        route(Routes.HOME)
        navigate(Routes.chat("session-2"))
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        route(Routes.CHAT)
        compose.runOnIdle { hub.value = null }
        compose.waitForIdle()
        route(Routes.PAIRING)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        route(Routes.PAIRING)
    }
}
