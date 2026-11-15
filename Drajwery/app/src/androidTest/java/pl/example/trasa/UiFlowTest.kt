package pl.example.trasa

import android.Manifest
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Testy interfejsu na emulatorze telefonu: dane z DemoProvider (bez sieci), prawdziwy ekran i prawdziwy ViewModel. */
class UiFlowTest {
    @get:Rule(order = 0) val perms: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() { App.override = DemoProvider; App.reset(); scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun tearDown() { scenario?.close(); App.override = null }

    private fun shot(name: String) = try {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, it) }
    } catch (e: Throwable) { }

    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun totalKmAppearsAndGrowsWithEachStop() {
        compose.onNodeWithText("Punkt początkowy (adres)").performTextInput("Mickiewicza 5, Białystok")
        compose.onNodeWithText("Punkt końcowy (adres)").performTextInput("Lipowa 3, Białystok")
        compose.onNodeWithText("Punkt końcowy (adres)").performImeAction()   // Enter na klawiaturze = przelicz
        waitFor("Trasa: "); shot("1-trasa-start-meta")
        val first = App.vm.totalKm!!
        compose.onNodeWithText("Przystanek 1").performTextInput("Rynek Kościuszki 1")
        compose.onNodeWithText("Przystanek 1").performImeAction()
        compose.waitUntil(15_000) { App.vm.order.size == 3 }
        assert(App.vm.totalKm!! >= first - 1e-6) { "trasa z przystankiem nie może być krótsza (nierówność trójkąta)" }
        shot("2-trasa-z-przystankiem")
    }
    @Test fun routeWithDeadlinesShowsLateWarningOrOrder() {
        compose.runOnUiThread {
            App.vm.startText = "A start"; App.vm.endText = "Z koniec"
            App.vm.stops.clear(); App.vm.stops.addAll(listOf("Punkt B @08:01", "Punkt C", ""))
            App.vm.departText = "08:00"; App.vm.plan(force = true)
        }
        compose.waitUntil(15_000) { App.vm.order.size == 4 }
        waitFor("przyjazd"); shot("3-terminy")
    }
    @Test fun importCsvAddsManyStopsWithoutLimit() {
        compose.runOnUiThread { App.vm.startText = "A start"; App.vm.endText = "Z koniec" }
        compose.runOnIdle { App.vm.importText("adres\n" + (1..60).joinToString("\n") { "Ulica $it, Białystok" }) }
        compose.waitUntil(60_000) { App.vm.order.size == 62 }
        shot("4-60-przystankow")
    }
    @Test fun openEndAndReturnToStartVariants() {
        compose.runOnUiThread { App.vm.startText = "A start"; App.vm.stops.clear(); App.vm.stops.addAll(listOf("B", "C", "")); App.vm.openEnd = true; App.vm.plan(force = true) }
        compose.waitUntil(15_000) { App.vm.order.size == 4 }          // start + 2 przystanki + sztuczny koniec
        assert(App.vm.dummyIdx == 3)
        compose.runOnUiThread { App.vm.openEnd = false; App.vm.returnToStart = true; App.vm.plan(force = true) }
        compose.waitUntil(15_000) { App.vm.dummyIdx == -1 && App.vm.order.size == 4 }
        assert(App.vm.points.first() == App.vm.points.last())
    }
    @Test fun mapTabRendersWithoutCrash() {
        compose.runOnUiThread { App.vm.startText = "A start"; App.vm.endText = "Z koniec"; App.vm.plan(force = true) }
        compose.waitUntil(15_000) { App.vm.order.size == 2 }
        compose.onNodeWithText("Mapa").performClick(); waitFor("Przytrzymaj mapę"); shot("5-mapa")
    }

    @Test fun splashShowsBrandNameThenTheApp() {
        scenario?.close(); App.splashDone = false; scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithText("DRAJWERY").assertExists()
        compose.onNodeWithText("PLANER TRAS DLA KIEROWCÓW").assertExists(); shot("0-ekran-startowy")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Ustawienia").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun summaryDialogShowsTachographData() {
        compose.runOnUiThread { App.vm.userName = "Jan"; App.vm.summary = TripSummary(1000.0, 1245.3, 245.3, 3 * 3600.0 + 12 * 60, 45 * 60.0, 240.0) }
        waitFor("Koniec trasy"); waitFor("Czas jazdy"); waitFor("3 h 12 min"); waitFor("45 min"); waitFor("245,3 km"); shot("6-podsumowanie")
    }
    @Test fun userNameShownAndHistorySuggestionsWork() {
        compose.runOnUiThread { App.vm.userName = "Janusz"; App.vm.history = listOf(HistoryEntry("Mickiewicza 5, Białystok", DemoProvider.fake("m"), 3, 1L)) }
        waitFor("Janusz")
        compose.onNodeWithText("Punkt początkowy (adres)").performTextInput("Mickie")
        waitFor("Mickiewicza 5, Białystok")                                  // podpowiedź z historii
        compose.onNodeWithText("Mickiewicza 5, Białystok", substring = true).performClick()
        compose.waitUntil(5_000) { App.vm.startText == "Mickiewicza 5, Białystok" }
        compose.runOnUiThread { App.vm.historyOn = false }                  // po wyłączeniu zapamiętywania podpowiedzi znikają
        assert(App.vm.suggestions("Mickie").isEmpty())
    }
}
