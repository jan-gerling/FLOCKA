package org.flocka.Utils

import java.util.concurrent.TimeUnit

object TimeMeasurement {
  /**
  Prints the time to run a given code block. Time in seconds, if not specified otherwise.
  */
  def measureTime[R](block: => R, timeConversion: Long => Long = TimeUnit.NANOSECONDS.toSeconds, timeUnit: TimeUnit, description: String = ""): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name
    val t1 = System.nanoTime()
    val elapsedTime: Double = timeConversion((t1 - t0))
    println((description + " took: " + elapsedTime + " " + timeUnit.toString))
    return result
  }
}