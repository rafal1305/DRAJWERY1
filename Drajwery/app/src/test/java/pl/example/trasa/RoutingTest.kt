package pl.example.trasa

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutingTest {
    private fun sq(x: DoubleArray) = Array(x.size) { i -> DoubleArray(x.size) { j -> Math.abs(x[i] - x[j]) } }

    @Test fun optimizerKeepsEndsAndFindsShortest() {
        assertEquals(listOf(0, 2, 3, 1, 4), RouteOptimizer.optimize(sq(doubleArrayOf(0.0, 3.0, 1.0, 2.0, 4.0))))
    }
    @Test fun optimizerHandlesManyStops() {
        val r = java.util.Random(7); val n = 150
        val p = List(n) { doubleArrayOf(r.nextDouble() * 100, r.nextDouble() * 100) }
        val m = Array(n) { i -> DoubleArray(n) { j -> Math.hypot(p[i][0] - p[j][0], p[i][1] - p[j][1]) } }
        val o = RouteOptimizer.optimize(m)
        assertEquals(0, o.first()); assertEquals(n - 1, o.last()); assertEquals((0 until n).toList(), o.sorted())
        assertTrue(RouteOptimizer.cost(o, m) < RouteOptimizer.cost((0 until n).toList(), m))
    }
    @Test fun parsesOsrmDistancesWithNull() {
        val m = OsmProvider.parseDistances(JSONArray("[[0,1200.5],[null,0]]"))
        assertEquals(1200.5, m[0][1], 1e-9); assertEquals(1e12, m[1][0], 0.0)
    }
    private val line = List(11) { LatLng(53.0 + it * 0.001, 23.0) }
    private fun dLon(m: Double) = m / (111320 * Math.cos(Math.toRadians(53.0)))

    @Test fun navLogicDetectsDeviationAndArrival() {
        val leg = Leg(line, 1100.0, 120.0); val t = line.last()
        assertEquals(NavEvent.OK, NavLogic.check(LatLng(53.0042, 23.0 + dLon(20.0)), t, leg))
        assertEquals(NavEvent.OFF_ROUTE, NavLogic.check(LatLng(53.0042, 23.0 + dLon(80.0)), t, leg))
        assertEquals(NavEvent.ARRIVED, NavLogic.check(LatLng(53.0100, 23.0), t, leg))
    }
    @Test fun offRouteThresholdGrowsWithSpeedAndAccuracy() {
        assertTrue(NavLogic.offThreshold(5.0, 30.0) > NavLogic.offThreshold(5.0, 3.0))
        assertTrue(NavLogic.offThreshold(60.0, 0.0) >= 90.0)
    }
    @Test fun parsesOsrmRouteWithSteps() {
        val j = JSONObject("""{"code":"Ok","routes":[
            {"distance":1000.0,"duration":90.0,"geometry":{"coordinates":[[23.0,53.0],[23.0,53.01]]},
             "legs":[{"steps":[{"name":"","distance":10.0,"maneuver":{"type":"depart","location":[23.0,53.0]}},
                               {"name":"Lipowa","distance":900.0,"maneuver":{"type":"turn","modifier":"right","location":[23.0,53.005]}},
                               {"name":"","distance":0.0,"maneuver":{"type":"arrive","location":[23.0,53.01]}}]}]},
            {"distance":1500.0,"duration":130.0,"geometry":{"coordinates":[[23.0,53.0],[23.01,53.0],[23.0,53.01]]}}]}""")
        val l = OsmProvider.parseOsrmRoute(j, false)
        assertEquals(3, l.steps.size); assertEquals("", Guidance.near(l.steps[0], Phrases()))
        assertEquals("Skręć w prawo, Lipowa", Guidance.near(l.steps[1], Phrases()))
        assertEquals("Dojechałeś do przystanku", Guidance.near(l.steps[2], Phrases()))
        assertEquals(53.005, l.steps[1].at.lat, 1e-9)
        val alt = OsmProvider.parseOsrmRoute(j, true); assertEquals(3, alt.points.size)
    }
}
