package pl.example.trasa

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun stopTextParsesDeadlineAndService() {
        val s = StopText.parse("Rynek 1, Białystok @14:30 ~10")
        assertEquals("Rynek 1, Białystok", s.address); assertEquals(14 * 60 + 30, s.deadlineMin); assertEquals(10, s.serviceMin)
        val p = StopText.parse("Lipowa 3"); assertNull(p.deadlineMin); assertNull(p.serviceMin)
    }
    @Test fun scheduleComputesArrivalsAndLateFlags() {
        val dur = arrayOf(doubleArrayOf(0.0, 600.0, 1200.0), doubleArrayOf(600.0, 0.0, 600.0), doubleArrayOf(1200.0, 600.0, 0.0))
        val e = Schedule.arrivals(listOf(0, 1, 2), dur, listOf(0.0, 5.0, 0.0), listOf(null, 8 * 60.0 + 5, null), 8 * 60.0)
        assertEquals(8 * 60 + 10.0, e[1].arrivalMin, 1e-9); assertTrue(e[1].late)
        assertEquals(8 * 60 + 25.0, e[2].arrivalMin, 1e-9)    // 10 min + 5 min postoju + 10 min
        assertEquals("08:25", Schedule.hhmm(e[2].arrivalMin)); assertEquals(14 * 60 + 5, Schedule.parseHhmm("14:05")); assertNull(Schedule.parseHhmm("25:00"))
    }
    @Test fun deadlinesChangeTheOrder() {
        // start 0, A=10, B=-5, meta 20: bez terminów najkrócej przez B, ale A ma termin 10 → A musi być pierwszy
        val x = doubleArrayOf(0.0, 10.0, -5.0, 20.0)
        val dur = Array(4) { i -> DoubleArray(4) { j -> Math.abs(x[i] - x[j]) } }
        val free = RouteOptimizer.optimizeTw(dur, DoubleArray(4), DoubleArray(4) { Double.MAX_VALUE }, 0.0)
        assertEquals(listOf(0, 2, 1, 3), free)
        val dl = doubleArrayOf(Double.MAX_VALUE, 10.0, Double.MAX_VALUE, Double.MAX_VALUE)
        assertEquals(listOf(0, 1, 2, 3), RouteOptimizer.optimizeTw(dur, DoubleArray(4), dl, 0.0))
        dl[1] = 4.0; assertNull(RouteOptimizer.optimizeTw(dur, DoubleArray(4), dl, 0.0))   // niewykonalne
    }
    @Test fun csvRoundTripAndImport() {
        val csv = Csv.export(listOf(CsvRow(1, "Rynek 1; Białystok", 53.1, 23.1, "08:00", 5, "14:30")))
        assertTrue(csv.startsWith("lp;adres;")); assertTrue(csv.contains("\"Rynek 1; Białystok\""))
        assertEquals(listOf("Rynek 1; Białystok @14:30 ~5"), Csv.parse(csv.replace("53.100000;23.100000;08:00;", "")
            .replace("lp;adres;szerokosc;dlugosc;przyjazd;", "lp;adres;")))
        assertEquals(listOf("Mickiewicza 5, Białystok", "Lipowa 3"), Csv.parse("Mickiewicza 5, Białystok\nLipowa 3\n"))
    }
    @Test fun splitterSeparatesClusters() {
        val pts = List(10) { LatLng(53.0 + it * 0.0001, 23.0) } + List(10) { LatLng(53.5 + it * 0.0001, 23.5) }
        val g = Splitter.split(pts, 2)
        assertEquals(2, g.size); assertTrue(g.all { it.size == 10 }); assertEquals((0 until 20).toList(), g.flatten().sorted())
    }
    @Test fun guidanceAnnouncesTwiceThenAdvances() {
        fun at(m: Double) = LatLng(53.0 + m / 110540.0, 23.0)
        val g = GuidanceState(listOf(Step(at(0.0), "depart"), Step(at(1000.0), "turn", "right", "Lipowa")))
        val said = ArrayList<String>()
        var m = 0.0; while (m <= 1000) { said += g.update(at(m), 14.0); m += 10 }
        assertEquals(2, said.size); assertTrue(said[0].startsWith("Za 300 metrów")); assertEquals("Skręć w prawo, Lipowa", said[1])
        val here = LatLng(0.0, 0.0)
        assertEquals("Skręć w lewo, Lipowa", Guidance.near(Step(here, "turn", "left", "Lipowa"), Phrases()))
        assertEquals("Na rondzie zjedź 2. zjazdem", Guidance.near(Step(here, "roundabout", exit = 2), Phrases()))
    }
    @Test fun userCanEditVoiceCommands() {
        val s = Step(LatLng(0.0, 0.0), "turn", "left", "Lipowa")
        val own = Phrases(far = "za {m}m {akcja}", left = "skręć w lewo", street = "")
        assertEquals("Za 300m skręć w lewo", Guidance.far(s, own, 300))      // przykład z ustawień: „za 300m skręć w lewo”
        assertEquals("Skręć w lewo", Guidance.near(s, own))
        val fix = PhraseFields.all.first { it.key == "left" }.set(Phrases(), "w lewo, proszę")
        assertEquals("W lewo, proszę, Lipowa", Guidance.near(s, fix))
    }
    @Test fun tripLogCountsDistanceDrivingAndStopTime() {
        fun at(m: Double) = LatLng(53.0 + m / 110540.0, 23.0)
        for (speed in listOf(0.0 to 14.0, -1.0 to -1.0)) {               // z prędkością GPS i bez niej (ocena po przemieszczeniu)
            val t = TripLog(1000.0); var ms = 0L
            repeat(30) { t.add(at(0.0), ms, if (speed.first < 0) -1.0 else 0.0, 5.0); ms += 2000 }
            for (i in 1..300) { t.add(at(28.0 * i), ms, if (speed.second < 0) -1.0 else 14.0, 5.0); ms += 2000 }
            repeat(150) { t.add(at(8400.0), ms, if (speed.first < 0) -1.0 else 0.0, 5.0); ms += 2000 }
            assertEquals(8400.0, t.distanceM, 1.0); assertEquals(600.0, t.drivingSec, 0.5); assertEquals(358.0, t.stopSec, 0.5)
            val sum = t.summary(9.0)
            assertEquals(1008.4, sum.odoEndKm!!, 1e-6); assertEquals(8.4, sum.distanceKm, 1e-3); assertEquals(9.0, sum.plannedKm!!, 0.0)
        }
        val j = TripLog(null); j.add(at(0.0), 0, -1.0); j.add(LatLng(60.0, 23.0), 2000, -1.0)   // skok GPS o setki km
        assertEquals(0.0, j.distanceM, 0.0); assertEquals(2.0, j.stopSec, 0.0)
        assertNull(j.summary(null).odoEndKm)
        assertEquals("2 h 05 min", TripFmt.hm(7500.0)); assertEquals("7 min", TripFmt.hm(420.0))
    }
    @Test fun addressHistoryRecordsSuggestsAndCanBeDisabled() {
        val h1 = History.record(emptyList(), "Rynek 1", LatLng(1.0, 2.0), 100)
        val h2 = History.record(h1, "rynek 1", LatLng(1.0, 2.0), 200)
        assertEquals(1, h2.size); assertEquals(2, h2[0].count); assertEquals("Rynek 1", h2[0].label)
        val h3 = History.record(h2, "Lipowa 3", LatLng(3.0, 4.0), 300)
        assertEquals(listOf("Lipowa 3", "Rynek 1"), h3.map { it.label })
        assertEquals(listOf("Rynek 1"), History.suggest(h3, "ryn", true).map { it.label })
        assertTrue(History.suggest(h3, "ryn", false).isEmpty())            // przełącznik „wyłącz”
        assertTrue(History.suggest(h3, "r", true).isEmpty()); assertTrue(History.suggest(h3, "rynek 1", true).isEmpty())
        assertSame(h3, History.record(h3, RouteVM.GPS_LABEL, LatLng(0.0, 0.0), 5))   // GPS nie trafia do historii
        assertNotNull(History.find(h3, " LIPOWA 3 "))
        var big = emptyList<HistoryEntry>(); for (i in 1..305) big = History.record(big, "Adres $i", LatLng(0.0, 0.0), i.toLong())
        assertEquals(300, big.size); assertEquals("Adres 305", big.first().label); assertNull(History.find(big, "Adres 1"))
    }
    @Test fun codecRoundTrips() {
        val i = Inputs("Start", null, "", LatLng(53.1, 23.2), listOf("A @10:00", "B"), true, false, mapOf("Punkt" to LatLng(1.0, 2.0)))
        assertEquals(i, Codec.inputs(Codec.inputs(i)))
        val s = Session(i, listOf("a", "b"), listOf("a", "b"), listOf(LatLng(1.0, 2.0), LatLng(3.0, 4.0)), listOf(0, 1), listOf(0), true, false,
            1000.0, 5000.0, 400.0, 60.0, 12.5)
        assertEquals(s, Codec.session(Codec.session(s)))
        val st = Settings(RouteOptions(Criterion.TIME, true, false, true, Vehicle.TRUCK), false, 7, "08:00", true,
            123456.7, Phrases(far = "za {m}m {akcja}", left = "skręć w lewo"), "Jan", false)
        assertEquals(st, Codec.settings(Codec.settings(st)))
        assertEquals("Kierowca", Codec.settings(org.json.JSONObject("{}")).userName); assertTrue(Codec.settings(org.json.JSONObject("{}")).historyOn)
        val hist = listOf(HistoryEntry("Rynek 1", LatLng(1.0, 2.0), 3, 99L))
        assertEquals(hist, Codec.history(Codec.history(hist)))
    }
    @Test fun demoProviderIsDeterministicAndConsistent() = kotlinx.coroutines.runBlocking {
        val a = DemoProvider.geocode("Mickiewicza 5").first().at
        assertEquals(a, DemoProvider.geocode("mickiewicza 5 ").first().at)
        val m = DemoProvider.matrix(listOf(a, DemoProvider.fake("B"), DemoProvider.fake("C")), RouteOptions())
        assertEquals(m.dist[0][1], m.dist[1][0], 1e-6); assertTrue(m.dur[0][2] > 0)
    }
}
