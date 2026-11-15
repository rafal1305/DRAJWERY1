package pl.example.trasa

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Jazda: sztuczne pozycje GPS podawane do ViewModelu na emulatorze – przystanki, zboczenie, objazd, bez mety. */
class NavFlowTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)
    private val vm get() = App.vm
    private fun ui(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun until(ms: Long = 15_000, c: () -> Boolean) { val t = System.currentTimeMillis(); while (!c()) { assertTrue("timeout", System.currentTimeMillis() - t < ms); Thread.sleep(100) } }

    @Before fun setUp() { App.override = DemoProvider; App.reset() }
    @After fun tearDown() { App.override = null }

    private fun plan(open: Boolean = false) {
        ui { vm.startGps = DemoProvider.fake("Start A"); if (!open) vm.endText = "Meta Z"; vm.openEnd = open
             vm.stops.clear(); vm.stops.addAll(listOf("Przystanek B", "Przystanek C", "")); vm.plan(force = true) }
        until { vm.order.size == (if (open) 4 else 4) }
    }

    @Test fun drivesThroughAllStopsAndFinishes() {
        plan(); ui { vm.startNav() }
        val coords = vm.coords
        ui { vm.onLocation(coords[vm.order[0]]) }                   // jesteśmy na starcie – wyznacza trasę do pierwszego przystanku
        until { vm.leg != null && !vm.busyNow }
        assertNotNull(vm.etaMin); assertNotNull(vm.remainingKm)
        val before = vm.remainingKm!!
        for (step in 1 until vm.order.size) {                        // „dojeżdżamy” kolejno do każdego punktu
            val target = vm.order[step]
            until { vm.leg != null && !vm.busyNow }
            ui { vm.onLocation(coords[target]) }
            until { vm.visited.size > step || !vm.navActive }
        }
        assertFalse(vm.navActive); assertTrue(vm.status.contains("Dotarłeś do celu"))
        assertTrue(before > 0)
    }

    @Test fun deviationTriggersReplanFromNewPosition() {
        plan(); ui { vm.startNav() }
        val c = vm.coords; ui { vm.onLocation(c[vm.order[0]]) }; until { vm.leg != null && !vm.busyNow }
        val off = LatLng(c[vm.order[0]].lat + 0.02, c[vm.order[0]].lng + 0.02)       // kilka km od trasy
        val oldStart = vm.leg!!.points.first()
        ui { vm.onLocation(off) }; ui { vm.onLocation(off) }          // dwa kolejne odczyty poza trasą
        until { vm.status.startsWith("Zboczono") && vm.status.contains("zaktualizowana") && !vm.busyNow }
        assertNotEquals(oldStart, vm.leg!!.points.first())            // nowa trasa zaczyna się w nowym miejscu
    }

    @Test fun blockedRoadSwitchesToAlternativeAndNoisyFixesAreIgnored() {
        plan(); ui { vm.startNav() }
        val c = vm.coords; ui { vm.onLocation(c[vm.order[0]]) }; until { vm.leg != null && !vm.busyNow }
        val before = vm.leg!!.meters
        ui { vm.onLocation(LatLng(0.0, 0.0), accuracy = 500.0) }      // niedokładny odczyt – ignorowany, brak przeliczania
        assertFalse(vm.status.contains("Zboczono"))
        ui { vm.blocked() }; until { vm.status.contains("objazd") && vm.status.contains("zaktualizowana") && !vm.busyNow }
        assertTrue(vm.leg!!.meters > before)                          // wariant objazdowy jest dłuższy
    }

    @Test fun openEndFinishesAtLastRealStop() {
        plan(open = true); ui { vm.startNav() }
        val c = vm.coords; ui { vm.onLocation(c[vm.order[0]]) }; until { vm.leg != null && !vm.busyNow }
        for (step in 1..2) { val t = vm.order[step]; until { !vm.busyNow }; ui { vm.onLocation(c[t]) }; until { vm.visited.size > step || !vm.navActive } }
        until { !vm.navActive }; assertTrue(vm.status.contains("Dotarłeś do celu"))
    }

    @Test fun addingAStopDuringTheDriveExtendsTheRoute() {
        plan(); ui { vm.startNav() }
        val c = vm.coords; ui { vm.onLocation(c[vm.order[0]]) }; until { vm.leg != null && !vm.busyNow }
        val n = vm.order.size
        ui { vm.addLive("Nowy przystanek D") }; until { vm.order.size == n + 1 }
        assertTrue(vm.points.contains("Nowy przystanek D"))
    }

    @Test fun sessionSurvivesAndCanBeResumed() {
        plan(); ui { vm.startNav() }
        val c = vm.coords; ui { vm.onLocation(c[vm.order[0]]) }; until { vm.leg != null && !vm.busyNow }
        Thread.sleep(600)                                              // zapis sesji w tle
        val s = App.storage?.loadSession(); assertNotNull(s)
        App.reset(); ui { vm.restore() }
        assertTrue(vm.resumable); ui { assertTrue(vm.resumeNav()) }
        assertTrue(vm.navActive); assertEquals(s!!.order, vm.order)
    }

    private fun driveAllStops() {
        val c = vm.coords; ui { vm.onLocation(c[vm.order[0]]) }; until { vm.leg != null && !vm.busyNow }
        for (step in 1 until vm.order.size) {
            val target = vm.order[step]
            until { vm.leg != null && !vm.busyNow }
            ui { vm.onLocation(c[target]) }
            until { vm.visited.size > step || !vm.navActive }
        }
        until { !vm.busyNow }
    }

    @Test fun tripSummaryHasDistanceDrivingAndStopTime() {
        plan(); ui { vm.startNav(1000.0) }
        val s = vm.coords[vm.order[0]]; var t = 0L
        fun at(m: Double) = LatLng(s.lat + m / 110540.0, s.lng)
        ui { vm.onLocation(s, 5.0, 0.0, t) }; until { vm.leg != null && !vm.busyNow }
        repeat(10) { t += 2000; ui { vm.onLocation(s, 5.0, 0.0, t) } }                      // 20 s postoju
        for (i in 1..30) { t += 2000; ui { vm.onLocation(at(28.0 * i), 5.0, 14.0, t) } }     // 60 s jazdy, 840 m
        ui { vm.stopNav() }
        val sum = vm.summary!!
        assertEquals(0.84, sum.distanceKm, 0.03); assertEquals(60.0, sum.drivingSec, 2.0); assertTrue(sum.stopSec in 18.0..22.0)
        assertEquals(1000.84, sum.odoEndKm!!, 0.03); assertEquals(1000.84, vm.lastOdoKm!!, 0.03)   // kolejna trasa zacznie od tego przebiegu
    }

    @Test fun visitedStopsGoToHistory() {
        ui { vm.historyOn = true }; plan(); ui { vm.startNav() }; driveAllStops()
        assertTrue(vm.history.any { it.label == "Przystanek B" })
        assertEquals("Przystanek B", vm.suggestions("przystanek").first { it.label == "Przystanek B" }.label)
    }

    @Test fun historySwitchedOffSavesNothing() {
        ui { vm.historyOn = false }; plan(); ui { vm.startNav() }; driveAllStops()
        assertTrue(vm.history.isEmpty()); assertTrue(vm.suggestions("przystanek").isEmpty())
    }
}
