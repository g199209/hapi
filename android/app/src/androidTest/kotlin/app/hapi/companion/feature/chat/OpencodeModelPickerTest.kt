package app.hapi.companion.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.hapi.companion.ui.theme.HapiTheme
import app.hapi.protocol.catalog.CatalogOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OpencodeModelPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun providerQualifiedModelsRemainSelectableInALongCatalog() {
        var selected: String? = "provider/model0"
        var config by mutableStateOf(SessionConfigUi(
            flavor = "opencode", active = true, controlledByUser = false,
            permissionMode = null, permissionModes = emptyList(), model = selected,
            modelOptions = (0..57).map { CatalogOption("provider/model$it", "Model $it · provider") },
            modelOptionsLoading = false, effort = null, effortOptions = null,
        ))
        compose.setContent { HapiTheme {
            SessionConfigSheetContent(config, {}, { selected = it; config = config.copy(model = it) }, {})
        } }
        compose.onNodeWithText("Model 57 · provider").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("provider/model57", selected) }
        compose.onNodeWithText("Model 0 · provider").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals("provider/model0", selected) }
    }
}
