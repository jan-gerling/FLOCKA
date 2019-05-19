package org.flocka.Services.User

import java.util.UUID.randomUUID

trait UserIdManager {
  final val supervisorIdMask: Long = 0xFF00000000000000L
  final val randomIdMask: Long = 0x00FFFFFFFFFFFFFFL
  final val randomIdBitLength = 56
  //-1 to avoid negative longs
  final val supervisorIdRange: Int = Math.pow(2, 64 - randomIdBitLength - 1).toInt

  /*
  Get the user actor supervisor id for the given userId.
  */
  final def extractSupervisorId(userId: Long): Long ={
    return (userId & supervisorIdMask) >> randomIdBitLength
  }

  /*
 Get the user actor id for the given userId.
 */
  final def extractUserActorId(userId: Long): Long ={
    return userId
  }


  /**
  Generate a userId for a user, this userId is also used to address the correct actor via the actor path.
  DO NOT ALTER A userID after it was assigned.
  The user id consists of two components:
  1. supervisor part (first 12 bit) - equivalent to the supervisor id, used to identify a supervisor for a specific userid
  2. randomUserId part (last 56 bit) - unique for each user actor of this supervisor
    */
  final def generateUserId(supervisorId: Long): Long = {
    val randomIdPart: Long = Math.abs(randomUUID().getMostSignificantBits) & randomIdMask
    return (supervisorId << randomIdBitLength) + randomIdPart
  }
}
