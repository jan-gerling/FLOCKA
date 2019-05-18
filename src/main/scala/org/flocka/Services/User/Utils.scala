package org.flocka.Services.User

object Utils {
  /*
   Measures the time in seconds to run a given code block.
    */
  def measureTime[R](block: => R): String = {
    val t0 = System.nanoTime()
    val result = block    // call-by-name
    val t1 = System.nanoTime()
    val elapsedSeconds: Double = ((t1 - t0) / 1000000000)
    return ("Elapsed time: " + elapsedSeconds + " seconds" )
  }
}
