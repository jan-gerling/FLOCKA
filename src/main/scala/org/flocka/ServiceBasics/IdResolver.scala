package org.flocka.ServiceBasics

/**
  * Defines and implements an id mapping schema.
  */
object IdResolver{
  case class InvalidIdException(Id: String) extends Exception("This Id: " + Id + " was already assigned.")

  final val repositoryThresholdExtension = 2

  final val shardIdBitLength        = 64 - repositoryIdBitLength - objectBitLength
  final val repositoryIdBitLength   = 40
  final val objectBitLength         = 12

  final val shardIdMask: Long       = 0xFFF0000000000000L
  final val repositoryIdMask: Long  = 0x000FFFFFFFFFF000L
  final val objectIdMask: Long      = 0x0000000000000FFFL

  final val maxRepositoryIdRange    = Math.pow(2, repositoryIdBitLength - 1).toLong
  final val maxShardIdRange         = Math.pow(2, shardIdBitLength - 1).toLong
  final val maxObjectIdRange        = Math.pow(2, objectBitLength - 1).toLong

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
}

class IdGenerator {
  protected var currentRepositoryThreshold: Int = 10
  protected val randomGenerator : scala.util.Random = scala.util.Random

  /**
    * Generates a random key and prepends the shardId given as parameter.
    * Preferable to use the overloaded generateId(numberShards: Int).
    */
  protected def generateId(shardId: Long): Long = {
    /*
    calculate and crop the ids for the repository and object from the generated uuid
     */
    val repositoryId: Long = randomGenerator.nextInt(currentRepositoryThreshold)//(uuid & IdManager.repositoryIdMask) % IdManager.repositoryIdRange(currentRepositoryThreshold)
    val objectId: Long = randomGenerator.nextInt(IdResolver.maxObjectIdRange.toInt)//(uuid & IdManager.objectIdMask)

    /*
    shift the id parts according to their id schema
     */
    val shardIdPart: Long = shardId << (IdResolver.repositoryIdBitLength + IdResolver.objectBitLength)
    val repositoryIdPart: Long = repositoryId << (IdResolver.objectBitLength)
    val objectIdPart: Long = objectId

    return shardIdPart + repositoryIdPart + objectIdPart
  }

  /**
    * Generates a random key and prepends the shardId given as parameter.
    * @param numberShards is the total number of shards in which the entity is being sharded.
    */
  final def generateId(numberShards: Int): Long = {
    if(numberShards > IdResolver.maxShardIdRange)
      throw new IllegalArgumentException("The number of shards is to high, a maximum of " + IdResolver.maxShardIdRange + " is allowed.")

    val shardId: Int = randomGenerator.nextInt(numberShards)
    return generateId(shardId.toLong)
  }

  final def increaseEntropy(): Unit ={
    currentRepositoryThreshold += IdResolver.repositoryThresholdExtension
    println("Increase entropy to: " + currentRepositoryThreshold)
  }
}