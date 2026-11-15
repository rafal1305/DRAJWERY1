package pl.example.trasa

import kotlin.math.*

object RouteOptimizer {
    fun cost(order: List<Int>, m: Array<DoubleArray>) = order.zipWithNext().sumOf { (a, b) -> m[a][b] }

    /** Punkt 0 = start, ostatni = koniec (stałe). Do 12 przystanków dokładnie, powyżej heurystyka (bez limitu). */
    fun optimize(m: Array<DoubleArray>): List<Int> {
        val n = m.size
        if (n <= 2) return (0 until n).toList()
        return if (n - 2 <= 12) exact(m) else heuristic(m)
    }

    /** Held-Karp: dokładne rozwiązanie dla małej liczby przystanków (także dla asymetrycznych odległości). */
    private fun exact(m: Array<DoubleArray>): List<Int> {
        val n = m.size; val k = n - 2; val full = 1 shl k; val inf = Double.MAX_VALUE
        val dp = Array(full) { DoubleArray(k) { inf } }; val par = Array(full) { IntArray(k) { -1 } }
        for (i in 0 until k) dp[1 shl i][i] = m[0][i + 1]
        for (mask in 1 until full) for (last in 0 until k) {
            if (mask and (1 shl last) == 0 || dp[mask][last] == inf) continue
            for (nx in 0 until k) {
                if (mask and (1 shl nx) != 0) continue
                val nm = mask or (1 shl nx); val c = dp[mask][last] + m[last + 1][nx + 1]
                if (c < dp[nm][nx]) { dp[nm][nx] = c; par[nm][nx] = last }
            }
        }
        var bl = 0; var best = inf
        for (l in 0 until k) { val c = dp[full - 1][l] + m[l + 1][n - 1]; if (c < best) { best = c; bl = l } }
        return rebuild(par, full - 1, bl, n)
    }

    private fun rebuild(par: Array<IntArray>, fullMask: Int, lastIdx: Int, n: Int): List<Int> {
        val mid = ArrayList<Int>(); var mask = fullMask; var l = lastIdx
        while (l != -1) { mid += l + 1; val p = par[mask][l]; mask = mask xor (1 shl l); l = p }
        return listOf(0) + mid.reversed() + (n - 1)
    }

    /**
     * Terminy ("najpóźniej do"): dokładnie, do 12 przystanków. Minimalizuje czas dojazdu do mety przy spełnieniu
     * wszystkich terminów. dur w minutach, service = postój w punkcie (min), t0 = godzina wyjazdu (min od północy).
     * Zwraca null, gdy terminów nie da się dotrzymać lub przystanków jest za dużo.
     */
    fun optimizeTw(dur: Array<DoubleArray>, service: DoubleArray, deadline: DoubleArray, t0: Double): List<Int>? {
        val n = dur.size
        if (n <= 2 || n - 2 > 12) return null
        val k = n - 2; val full = 1 shl k; val inf = Double.MAX_VALUE
        val dp = Array(full) { DoubleArray(k) { inf } }; val par = Array(full) { IntArray(k) { -1 } } // dp = czas odjazdu z punktu
        for (i in 0 until k) { val arr = t0 + dur[0][i + 1]; if (arr <= deadline[i + 1]) dp[1 shl i][i] = arr + service[i + 1] }
        for (mask in 1 until full) for (last in 0 until k) {
            val cur = dp[mask][last]; if (cur == inf) continue
            for (nx in 0 until k) {
                if (mask and (1 shl nx) != 0) continue
                val arr = cur + dur[last + 1][nx + 1]; if (arr > deadline[nx + 1]) continue
                val dep = arr + service[nx + 1]; val nm = mask or (1 shl nx)
                if (dep < dp[nm][nx]) { dp[nm][nx] = dep; par[nm][nx] = last }
            }
        }
        var bl = -1; var best = inf
        for (l in 0 until k) {
            val v = dp[full - 1][l]; if (v == inf) continue
            val arr = v + dur[l + 1][n - 1]
            if (arr <= deadline[n - 1] && arr < best) { best = arr; bl = l }
        }
        return if (bl == -1) null else rebuild(par, full - 1, bl, n)
    }

    /** Najbliższy sąsiad + 2-opt + przenoszenie pojedynczych punktów; koszty zmian w O(1) (odległości uśrednione). */
    private fun heuristic(m: Array<DoubleArray>): List<Int> {
        val n = m.size
        val s = Array(n) { i -> DoubleArray(n) { j -> (m[i][j] + m[j][i]) / 2 } }
        val used = BooleanArray(n); used[0] = true; used[n - 1] = true
        val o = ArrayList<Int>(n); o.add(0)
        for (step in 1 until n - 1) {
            val cur = o.last(); var best = -1
            for (c in 1 until n - 1) if (!used[c] && (best == -1 || m[cur][c] < m[cur][best])) best = c
            o.add(best); used[best] = true
        }
        o.add(n - 1)
        val nn = o.toList()
        var improved = true; var passes = 0
        while (improved && passes++ < 100) {
            improved = false
            for (i in 1 until n - 2) for (j in i + 1 until n - 1) {   // 2-opt
                val a = o[i - 1]; val b = o[i]; val c = o[j]; val d = o[j + 1]
                if (s[a][c] + s[b][d] < s[a][b] + s[c][d] - 1e-9) { o.subList(i, j + 1).reverse(); improved = true }
            }
            var i = 1                                                  // or-opt
            while (i < n - 1) {
                val v = o[i]; val a = o[i - 1]; val b = o[i + 1]
                val gain = s[a][v] + s[v][b] - s[a][b]
                var bestDelta = 1e-9; var bestP = -1
                for (p in 0 until n - 1) {
                    if (p == i - 1 || p == i) continue
                    val x = o[p]; val y = o[p + 1]
                    val add = s[x][v] + s[v][y] - s[x][y]
                    if (gain - add > bestDelta) { bestDelta = gain - add; bestP = p }
                }
                if (bestP >= 0) { o.removeAt(i); o.add(if (bestP < i) bestP + 1 else bestP, v); improved = true }
                i++
            }
        }
        val res = o.toList()
        return if (cost(res, m) <= cost(nn, m)) res else nn
    }
}

/** Podział przystanków na k tras (np. dla kilku kierowców) – grupowanie k-średnich po współrzędnych. */
object Splitter {
    fun split(pts: List<LatLng>, k: Int): List<List<Int>> {
        val n = pts.size
        val kk = k.coerceIn(1, maxOf(1, n))
        if (kk == 1 || n == 0) return listOf(pts.indices.toList())
        val lat0 = pts.map { it.lat }.average()
        val x = DoubleArray(n) { pts[it].lng * cos(Math.toRadians(lat0)) }; val y = DoubleArray(n) { pts[it].lat }
        val cx = DoubleArray(kk); val cy = DoubleArray(kk)
        cx[0] = x[0]; cy[0] = y[0]
        for (c in 1 until kk) {                       // inicjalizacja: punkt najdalszy od wybranych środków
            var best = 0; var bd = -1.0
            for (i in 0 until n) {
                var d = Double.MAX_VALUE
                for (q in 0 until c) d = minOf(d, hypot(x[i] - cx[q], y[i] - cy[q]))
                if (d > bd) { bd = d; best = i }
            }
            cx[c] = x[best]; cy[c] = y[best]
        }
        val asg = IntArray(n)
        repeat(30) {
            for (i in 0 until n) {
                var b = 0; var bd = Double.MAX_VALUE
                for (c in 0 until kk) { val d = hypot(x[i] - cx[c], y[i] - cy[c]); if (d < bd) { bd = d; b = c } }
                asg[i] = b
            }
            for (c in 0 until kk) {
                val idx = (0 until n).filter { asg[it] == c }
                if (idx.isNotEmpty()) { cx[c] = idx.map { x[it] }.average(); cy[c] = idx.map { y[it] }.average() }
            }
        }
        return (0 until kk).map { c -> (0 until n).filter { asg[it] == c } }.filter { it.isNotEmpty() }
    }
}
