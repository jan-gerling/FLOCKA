package org.flocka.Services.User

import java.util.UUID.randomUUID

object IdManager {
  final val shardIdBitLength = 64 - repositoryIdBitLength - objectBitLength
  final val repositoryIdBitLength = 40
  final val objectBitLength = 12

  final val shardIdMask: Long = 0xFFFL << (repositoryIdBitLength + objectBitLength)
  final val repositoryIdMask: Long = 0x000FFFFFFFFFF000L
  final val objectIdMask: Long = 0xFFFL

  final val randomGenerator : scala.util.Random = scala.util.Random

  //-1 to avoid negative longs
  final val shardIdRange: Long = Math.pow(2, shardIdBitLength - 1).toLong

  /**
  * Takes a key, for example userId, and returns the shard to which it belongs. Use in extractShardId
  */
  final def extractShardId(key: Long): Long ={
    return (key & shardIdMask) >> (repositoryIdBitLength + objectBitLength)
  }

  /*
   * Takes a key, for example userId, and returns the repository in which this key resides.
   * The key may then be used to obtain UserState
   */
  final def extractRepositoryId(key: Long): Long ={
    return (key & repositoryIdMask) >> objectBitLength
  }

  /**
    * Generates a random key and prepends the shardId given as parameter.
    * Preferable to use the overloaded generateId(numberShards: Int).
    */
  final def generateId(shardId: Long): Long = {
    val repositoryIdPart: Long = Math.abs(randomUUID().getMostSignificantBits) & repositoryIdMask
    val objectIdPart: Long =  Math.abs(randomUUID().getMostSignificantBits) & objectIdMask
    return (shardId << repositoryIdBitLength + objectBitLength) + repositoryIdPart + objectIdPart
  }

  /**
    * Generates a random key and prepends the shardId given as parameter.
    * @param numberShards is the total number of shards in which the entity is being sharded.
    */
  final def generateId(numberShards: Int): Long = {
    if(numberShards > shardIdRange)
      throw new IllegalArgumentException("THe number of shards is to high, a maximum of " + shardIdRange + " is allowed.")

    val shardId: Int = randomGenerator.nextInt(numberShards) + 1
    val id = generateId(shardId.toLong)
    return id
  }
}