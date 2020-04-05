package com.github.mbuzdalov.opl.transition

import com.github.mbuzdalov.opl.MathEx.{logFactorial => lF}

object BoundedProbabilityFinder extends TransitionProbabilityFinder {
  override def find(n: Int, lambda: Int, d: Int, change: Int, target: Array[Double], aux: Aux): Unit = {
    val l = math.max((change + 1) / 2, change - n + d)
    val u = math.min(change, d)
    val m = u - l + 2

    val common = lF(d) + lF(n - d) - lF(n) + lF(change) + lF(n - change)
    val unit = aux.unit
    val prob = aux.prob
    var k = 0
    var sum = 0.0
    while (k <= u - l) {
      val curr = math.exp(common - lF(k + l) - lF(d - k - l) - lF(change - k - l) - lF(n - d - change + k + l))
      k += 1
      unit(k) = curr
      prob(k) = 0
      sum += curr
    }

    unit(0) = 1 - sum
    prob(0) = 1
    if (unit(0) < 1)
      ProbabilityVectorUtils.multiplyByPower(lambda, unit, prob, m)

    var i = u - l
    while (i >= 0) {
      target(i) = prob(i + 1)
      i -= 1
    }
  }

  case class AuxImpl(unit: Array[Double], prob: Array[Double])
  override type Aux = AuxImpl
  override def newAuxiliaryData(n: Int): Aux = AuxImpl(new Array(n + 1), new Array(n + 1))
}
