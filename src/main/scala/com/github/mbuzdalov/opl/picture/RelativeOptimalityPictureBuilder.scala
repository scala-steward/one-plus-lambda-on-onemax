package com.github.mbuzdalov.opl.picture

import java.awt.image.BufferedImage
import java.nio.file.{Files, Path}

import javax.imageio.ImageIO

import scala.util.Using

import com.github.mbuzdalov.opl.computation.ComputationResult
import com.github.mbuzdalov.opl.util.Viridis

object RelativeOptimalityPictureBuilder {
  def apply[@specialized P](source: ComputationResult[P],
                            ordinateValues: Array[P],
                            xMin: Int, xMax: Int,
                            target: Path): Unit = {
    val image = new BufferedImage(xMax - xMin + 1, ordinateValues.length, BufferedImage.TYPE_INT_RGB)
    var xIndex = 0
    while (xIndex + xMin <= xMax) {
      val bestValue = source.optimalExpectation(xIndex + xMin)
      var yIndex = 0
      while (yIndex < ordinateValues.length) {
        val currValue = source.optimalExpectationForParameter(xIndex + xMin, ordinateValues(yIndex))
        val value01 = math.exp(bestValue - currValue)
        image.setRGB(xIndex, yIndex, Viridis(value01))
        yIndex += 1
      }
      xIndex += 1
    }
    Using.resource(Files.newOutputStream(target))(stream => ImageIO.write(image, "png", stream))
  }
}