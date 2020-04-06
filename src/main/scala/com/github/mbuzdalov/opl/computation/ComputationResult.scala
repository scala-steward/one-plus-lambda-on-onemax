package com.github.mbuzdalov.opl.computation

import com.github.mbuzdalov.opl.MathEx.{logFactorial => lF}

trait ComputationResult[@specialized P] {
  def problemSize: Int
  def populationSize: Int

  def optimalParameter(distance: Int): P
  def optimalExpectation(distance: Int): Double

  lazy val expectedRunningTime: Double = {
    val n = problemSize
    val log2powN = math.log(2) * n
    var start = 0
    var result = 0.0
    while (start < n) {
      start += 1
      val currProb = math.exp(lF(n) - lF(start) - lF(n - start) - log2powN)
      result += currProb * optimalExpectation(start)
    }
    result
  }

  // this seems to be a universal thing, as everything else derives from it
  def optimalExpectationForBitFlips(distance: Int, flips: Int): Double

  def optimalExpectationForParameter(distance: Int, parameter: P): Double
}
