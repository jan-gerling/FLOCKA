package org.flocka.Services.User

trait UserIdManager {
  final val rebuildSupervisorIdMask: Long = 0xFF00000000000000L
  final val supervisorIdMask: Long = 0x00000000000000FFL
  final val randomIdMask: Long = 0x00FFFFFFFFFFFFFFL
  final val randomIdBitLength = 56
  //-1 to avoid negative longs
  final val supervisorIdRange: Int = Math.pow(2, 64 - randomIdBitLength - 1).toInt

  /*
  Get the user actor supervisor id for the given userId.
  */
  final def extractSupervisorId(userId: Long): Long ={
    return (userId & rebuildSupervisorIdMask) >> randomIdBitLength
  }

  /*
 Get the user actor id for the given userId.
 */
  final def extractUserActorId(userId: Long): Long ={
    return userId
  }
}
