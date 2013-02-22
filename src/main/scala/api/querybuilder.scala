package reactivemongo.api

import org.jboss.netty.buffer.ChannelBuffer
import reactivemongo.bson._
import DefaultBSONHandlers._
import reactivemongo.core.protocol.QueryFlags
import reactivemongo.utils._
import reactivemongo.core.netty._


sealed trait SortOrder
object SortOrder {
  case object Ascending extends SortOrder
  case object Descending extends SortOrder
}

/**
 * A helper to make the query options.
 *
 * You may use the methods to set the fields of this class, or set them directly.
 *
 * @param skipN The number of documents to skip.
 * @param batchSizeN The upper limit on the number of documents to retrieve per batch.
 * @param flagsN The query flags.
 */
case class QueryOpts(
  skipN: Int = 0,
  batchSizeN: Int = 0,
  flagsN: Int = 0
) {
  /** Sets the number of documents to skip. */
  def skip(n: Int) = copy(skipN = n)
  /** Sets an upper limit on the number of documents to retrieve per batch. Defaults to 0 (meaning no upper limit - MongoDB decides). */
  def batchSize(n: Int) = copy(batchSizeN = n)
  /** Sets the query flags. */
  def flags(n: Int) = copy(flagsN = n)

  /** Toggles TailableCursor: Makes the cursor not to close after all the data is consumed. */
  def tailable        = copy(flagsN = flagsN ^ QueryFlags.TailableCursor)
  /** Toggles SlaveOk: The query is might be run on a secondary. */
  def slaveOk         = copy(flagsN = flagsN ^ QueryFlags.SlaveOk)
  /** Toggles OplogReplay */
  def oplogReplay     = copy(flagsN = flagsN ^ QueryFlags.OplogReplay)
  /** Toggles NoCursorTimeout: The cursor will not expire automatically */
  def noCursorTimeout = copy(flagsN = flagsN ^ QueryFlags.NoCursorTimeout)
  /**
   * Toggles AwaitData: Block a little while waiting for more data instead of returning immediately if no data.
   * Use along with TailableCursor.
   */
  def awaitData       = copy(flagsN = flagsN ^ QueryFlags.AwaitData)
  /** Toggles Exhaust */
  def exhaust         = copy(flagsN = flagsN ^ QueryFlags.Exhaust)
  /** Toggles Partial: The response can be partial - if a shard is down, no error will be thrown. */
  def partial         = copy(flagsN = flagsN ^ QueryFlags.Partial)
}