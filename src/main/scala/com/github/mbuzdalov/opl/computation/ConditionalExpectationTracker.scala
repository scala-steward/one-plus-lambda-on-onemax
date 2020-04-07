package com.github.mbuzdalov.opl.computation

private class ConditionalExpectationTracker(expectations: Array[Double]) {
  private[this] var updateProbability, conditionalExpectation: Double = _

  final def reset(): Unit = {
    updateProbability = 0
    conditionalExpectation = 0
  }

  def receiveProbability(newDistance: Int, probability: Double): Unit = {
    updateProbability += probability
    if (newDistance >= 1)
      conditionalExpectation += probability * expectations(newDistance - 1)
  }

  def getConditionalExpectation: Double = conditionalExpectation
  def getUpdateProbability: Double = updateProbability
}
