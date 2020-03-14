package com.github.mbuzdalov.opl

class OnePlusLambda(n: Int, lambda: Int) {
  private[this] val logChoose = new MathEx.LogChoose(n)
  private[this] val optimalTimeCache, driftMaximizingCache = Array.fill(n)(Double.NaN)
  private[this] val optimalByStrength, driftMaximizingByStrength, driftByStrength = Array.fill(n, n)(Double.NaN)

  computeEverything()

  private def multiplyInPlace(a: Array[Double], b: Array[Double], norm: Double): Unit = {
    var aa, bb, sum = 0.0
    var i = 0
    while (i < a.length) {
      aa += a(i)
      bb += b(i)
      a(i) = aa * b(i) + bb * a(i) - a(i) * b(i)
      sum += a(i)
      i += 1
    }
    sum /= norm
    i = 0
    while (i < a.length) {
      a(i) /= sum
      i += 1
    }
  }

  private def multiplyByPower(power: Int, unit: Array[Double], result: Array[Double], norm: Double): Unit = {
    var p = power
    while (p > 1) {
      if ((p & 1) == 1) multiplyInPlace(result, unit, norm)
      multiplyInPlace(unit, unit, norm)
      p >>>= 1
    }
    if (p == 1) multiplyInPlace(result, unit, norm)
  }

  def optimalTime(d: Int): Double = if (d == 0) 0.0 else optimalTimeCache(d - 1)

  def optimalExpectedTime: Double = {
    val logAll = math.log(2) * n
    (0 to n).map(d => optimalTime(d) * math.exp(logChoose(n, d) - logAll)).sum
  }

  def driftOptimalTime(d: Int): Double = if (d == 0) 0.0 else driftMaximizingCache(d - 1)

  def driftOptimalExpectedTime: Double = {
    val logAll = math.log(2) * n
    (0 to n).map(d => driftOptimalTime(d) * math.exp(logChoose(n, d) - logAll)).sum
  }

  def optimalTime(d: Int, l: Int): Double =
    if (d == 0)
      0.0
    else if (l == 0)
      Double.PositiveInfinity
    else
      optimalByStrength(d - 1)(l - 1)

  def drift(d: Int, l: Int): Double =
    if (d == 0 || l == 0)
      0.0
    else
      driftByStrength(d - 1)(l - 1)

  def driftOptimalTime(d: Int, l: Int): Double =
    if (d == 0)
      0.0
    else if (l == 0)
      Double.PositiveInfinity
    else
      driftMaximizingByStrength(d - 1)(l - 1)

  private def computeEverything(): Unit = {
    for (d <- 1 to n) {
      var bestDriftValue = -1.0
      var bestDriftIndex = -1
      var minOptimal = Double.PositiveInfinity
      var change = 1
      while (change <= n) {
        compute(d, change)
        minOptimal = math.min(minOptimal, optimalByStrength(d - 1)(change - 1))
        val driftValue = driftByStrength(d - 1)(change - 1)
        if (bestDriftValue < driftValue) {
          bestDriftValue = driftValue
          bestDriftIndex = change
        }
        change += 1
      }
      optimalTimeCache(d - 1) = minOptimal
      driftMaximizingCache(d - 1) = driftMaximizingByStrength(d - 1)(bestDriftIndex - 1)
    }
  }

  private def compute(d: Int, change: Int): Unit = {
    val lower = math.max(0, change - n + d)
    val upper = math.min(change, d)
    val cnc = logChoose(n, change)
    val prob = Array.tabulate(upper - lower + 1)(okay => math.exp(logChoose(d, okay + lower) + logChoose(n - d, change - okay - lower) - cnc))
    val norm = prob.sum
    assert(math.abs(norm - 1) < 1e-9, s"prob.sum = ${prob.sum} at n=$n, d=$d, change=$change")
    multiplyByPower(lambda - 1, prob.clone(), prob, norm)

    var updateSumOptimal, updateSumDriftOptimal, drift = 0.0
    var updateProb = 0.0
    var i = 0
    while (i < prob.length) {
      val okay = lower + i
      val newD = d - 2 * okay + change
      val pi = prob(i)
      if (newD < d) {
        updateProb += pi
        if (newD > 0) {
          updateSumOptimal += optimalTimeCache(newD - 1) * pi
          updateSumDriftOptimal += driftMaximizingCache(newD - 1) * pi
        }
        drift += (d - newD) * pi
      }
      i += 1
    }

    if (updateProb < 1e-9) {
      optimalByStrength(d - 1)(change - 1) = Double.PositiveInfinity
      driftMaximizingByStrength(d - 1)(change - 1) = Double.PositiveInfinity
      driftByStrength(d - 1)(change - 1) = 0
    } else {
      optimalByStrength(d - 1)(change - 1) = (1 + updateSumOptimal) / updateProb
      driftMaximizingByStrength(d - 1)(change - 1) = (1 + updateSumDriftOptimal) / updateProb
      driftByStrength(d - 1)(change - 1) = drift
    }
  }
}
