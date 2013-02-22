package reactivemongo.api

import reactivemongo.bson.buffer.ReadableBuffer
import reactivemongo.bson.BSONValue
import reactivemongo.bson.buffer.DefaultBufferHandler
import reactivemongo.bson.BSONDocument
import reactivemongo.core.protocol._
import reactivemongo.core.netty._
import reactivemongo.bson.BSONDocumentWriter
import reactivemongo.bson.BSONDocumentReader
import reactivemongo.bson.buffer.ArrayBSONBuffer
import reactivemongo.core.commands.GetLastError
import reactivemongo.utils.EitherMappableFuture._
import play.api.libs.iteratee._
import scala.concurrent.{ ExecutionContext, Future }
import reactivemongo.core.commands.LastError
import reactivemongo.core.netty.BufferSequence
import org.jboss.netty.buffer.ChannelBuffer
import scala.util._
import reactivemongo.bson.buffer.WritableBuffer
import reactivemongo.core.commands.GetLastError

trait BufferWriter[-T] {
  def write[B <: WritableBuffer](t: T, buffer: B): B
}

trait BufferReader[+T] {
  def read(buffer: ReadableBuffer): T
}

trait GenericReader[S, T] {
  def read(s: S): T
}

trait GenericWriter[T, S] {
  def write(t: T): S
}

/**
 * A MongoDB Collection. You should consider the default implementation, [[reactivemongo.api.collections.default.BSONCollection]].
 *
 * Example using the default implementation (BSONCollection)
 *
 * {{{
 * import play.api.libs.iteratee.Iteratee
 * import reactivemongo.api.MongoConnection
 * import reactivemongo.bson._
 *
 * object Samples {
 * val connection = MongoConnection(List("localhost"))
 *
 * // Gets a reference to the database "plugin"
 * val db = connection("plugin")
 *
 * // Gets a reference to the collection "acoll"
 * // By default, you get a BSONCollection.
 * val collection = db("acoll")
 *
 * def listDocs() = {
 * // Select only the documents which field 'firstName' equals 'Jack'
 * val query = BSONDocument(
 * "firstName" -> "Jack")
 *
 * // Select only the field 'lastName'
 * val filter = BSONDocument(
 * "lastName" -> 1,
 * "_id" -> 0)
 *
 * // Get a cursor of BSONDocuments
 * val cursor = collection.find(query, filter).cursor
 * // Let's enumerate this cursor and print a readable representation of each document in the response
 * cursor.enumerate.apply(Iteratee.foreach { doc =>
 * println("found document: " + BSONDocument.pretty(doc))
 * })
 *
 * // Or, the same with getting a list
 * val cursor2 = collection.find(query, filter).cursor
 * val futureList = cursor.toList
 * futureList.map { list =>
 * list.foreach { doc =>
 * println("found document: " + BSONDocument.pretty(doc))
 * }
 * }
 * }
 * }
 * }}}
 */
trait Collection {
  /** The database which this collection belong to. */
  def db: DB
  /** The name of the collection. */
  def name: String
  /** The default failover strategy for the methods of this collection. */
  def failoverStrategy: FailoverStrategy

  /** Gets the full qualified name of this collection. */
  def fullCollectionName = db.name + "." + name

  /**
   * Gets another implementation of this collection.
   * An implicit CollectionProducer[C] must be present in the scope, or it will be the default implementation ([[reactivemongo.api.collections.default.BSONCollection]]).
   *
   * @param failoverStrategy Overrides the default strategy.
   */
  def as[C <: Collection](failoverStrategy: FailoverStrategy = failoverStrategy)(implicit producer: CollectionProducer[C] = collections.default.BSONCollectionProducer): C = {
    producer.apply(db, name, failoverStrategy)
  }

  /**
   * Gets another collection in the current database.
   * An implicit CollectionProducer[C] must be present in the scope, or it will be the default implementation ([[reactivemongo.api.collections.default.BSONCollection]]).
   *
   * @param name The other collection name.
   * @param failoverStrategy Overrides the default strategy.
   */
  def sister[C <: Collection](name: String, failoverStrategy: FailoverStrategy = failoverStrategy)(implicit producer: CollectionProducer[C] = collections.default.BSONCollectionProducer): C = {
    producer.apply(db, name, failoverStrategy)
  }
}

/**
 * A Producer of [[Collection]] implementation.
 *
 * This is used to get an implementation implicitly when getting a reference of a [[Collection]].
 */
trait CollectionProducer[+C <: Collection] {
  def apply(db: DB, name: String, failoverStrategy: FailoverStrategy = FailoverStrategy()): C
}

trait GenericCollectionProducer[Structure, Reader[_], Writer[_], +C <: GenericCollection[Structure, Reader, Writer]] extends CollectionProducer[C]

trait GenericHandlers[Structure, Reader[_], Writer[_]] {
  case class GenericBufferWriter[T](writer: Writer[T]) extends BufferWriter[T] {
    val structureWriter = StructureWriter(writer)
    def write[B <: WritableBuffer](t: T, buffer: B): B =
      StructureBufferWriter.write(structureWriter.write(t), buffer)
  }
  case class GenericBufferReader[T](reader: Reader[T]) extends BufferReader[T] {
    val structureReader = StructureReader(reader)
    def read(buffer: ReadableBuffer): T =
      structureReader.read(StructureBufferReader.read(buffer))
  }

  def BufferWriterInstance[T](writer: Writer[T]) = GenericBufferWriter(writer)
  def BufferReaderInstance[T](reader: Reader[T]) = GenericBufferReader(reader)

  def StructureBufferReader: BufferReader[Structure]
  def StructureBufferWriter: BufferWriter[Structure]

  def StructureReader[T](reader: Reader[T]): GenericReader[Structure, T]
  def StructureWriter[T](writer: Writer[T]): GenericWriter[T, Structure]
}

/**
 * A Collection that provides default methods using a `Structure` (like [[reactivemongo.bson.BSONDocument]], or a Json document, etc.).
 *
 * Some methods of this collection accept instances of `Reader[T]` and `Writer[T]`, that transform any `T` instance into a `Structure` and vice-versa.
 * The default implementation of [[Collection]], [[reactivemongo.api.collections.bson.BSONCollection]], extends this trait.
 *
 * @tparam Structure The structure that will be turned into BSON (and vice versa), usually a [[reactivemongo.bson.BSONDocument]] or a Json document.
 * @tparam Reader A `Reader[T]` that produces a `T` instance from a `Structure`.
 * @tparam Writer A `Writer[T]` that produces a `Structure` instance from a `T`.
 */
trait GenericCollection[Structure, Reader[_], Writer[_]] extends Collection with GenericHandlers[Structure, Reader, Writer] {
  def failoverStrategy: FailoverStrategy

  private def writeDoc(doc: Structure): ChannelBuffer = {
    val buffer = ChannelBufferWritableBuffer()
    StructureBufferWriter.write(doc, buffer).buffer
  }

  private def writeDoc[T](doc: T, writer: Writer[T]) = BufferWriterInstance(writer).write(doc, ChannelBufferWritableBuffer()).buffer

  def genericQueryBuilder: GenericQueryBuilder[Structure, Reader, Writer]

  /**
   * Find the documents matching the given criteria.
   *
   * This method accepts any query and projection object, provided that there is an implicit `Writer[S]` typeclass for handling them in the scope.
   *
   * Please take a look to the [[http://www.mongodb.org/display/DOCS/Querying mongodb documentation]] to know how querying works.
   *
   * @tparam S the type of the selector (the query). An implicit `Writer[S]` typeclass for handling it has to be in the scope.
   *
   * @param query The selector query.
   *
   * @return a [[GenericQueryBuilder]] that you can use to to customize the query. You can obtain a cursor by calling the method [[reactivemongo.api.Cursor]] on this query builder.
   */
  def find[S](selector: S)(implicit swriter: Writer[S]): GenericQueryBuilder[Structure, Reader, Writer] =
    genericQueryBuilder.query(selector)

  /**
   * Find the documents matching the given criteria.
   *
   * This method accepts any query and projection object, provided that there is an implicit `Writer[S]` typeclass for handling them in the scope.
   *
   * Please take a look to the [[http://www.mongodb.org/display/DOCS/Querying mongodb documentation]] to know how querying works.
   *
   * @tparam S the type of the selector (the query). An implicit `Writer[S]` typeclass for handling it has to be in the scope.
   * @tparam P the type of the projection object. An implicit `Writer[P]` typeclass for handling it has to be in the scope.
   *
   * @param query The selector query.
   * @param projection Get only a subset of each matched documents. Defaults to None.
   *
   * @return a [[GenericQueryBuilder]] that you can use to to customize the query. You can obtain a cursor by calling the method [[reactivemongo.api.Cursor]] on this query builder.
   */
  def find[S, P](selector: S, projection: P)(implicit swriter: Writer[S], pwriter: Writer[P]): GenericQueryBuilder[Structure, Reader, Writer] =
    genericQueryBuilder.query(selector).projection(projection)

  /**
   * Inserts a document into the collection and wait for the [[reactivemongo.core.commands.LastError]] result.
   *
   * Please read the documentation about [[reactivemongo.core.commands.GetLastError]] to know how to use it properly.
   *
   * @tparam T the type of the document to insert. An implicit `Writer[T]` typeclass for handling it has to be in the scope.
   *
   * @param document the document to insert.
   * @param writeConcern the [[reactivemongo.core.commands.GetLastError]] command message to send in order to control how the document is inserted. Defaults to GetLastError().
   *
   * @return a future [[reactivemongo.core.commands.LastError]] that can be used to check whether the insertion was successful.
   */
  def insert[T](document: T, writeConcern: GetLastError = GetLastError())(implicit writer: Writer[T], ec: ExecutionContext): Future[LastError] = {
    val op = Insert(0, fullCollectionName)
    val bson = writeDoc(document, writer)
    val checkedWriteRequest = CheckedWriteRequest(op, BufferSequence(bson), writeConcern)
    Failover(checkedWriteRequest, db.connection.mongosystem, failoverStrategy).future.mapEither(LastError.meaningful(_))
  }

  /**
   * Inserts a document into the collection and wait for the [[reactivemongo.core.commands.LastError]] result.
   *
   * Please read the documentation about [[reactivemongo.core.commands.GetLastError]] to know how to use it properly.
   *
   * @param document the document to insert.
   * @param writeConcern the [[reactivemongo.core.commands.GetLastError]] command message to send in order to control how the document is inserted. Defaults to GetLastError().
   *
   * @return a future [[reactivemongo.core.commands.LastError]] that can be used to check whether the insertion was successful.
   */
  def insert(document: Structure, writeConcern: GetLastError)(implicit ec: ExecutionContext): Future[LastError] = {
    val op = Insert(0, fullCollectionName)
    val bson = writeDoc(document)
    val checkedWriteRequest = CheckedWriteRequest(op, BufferSequence(bson), writeConcern) //TODO
    Failover(checkedWriteRequest, db.connection.mongosystem, failoverStrategy).future.mapEither(LastError.meaningful(_))
  }

  /**
   * Inserts a document into the collection and wait for the [[reactivemongo.core.commands.LastError]] result.
   *
   * @param document the document to insert.
   *
   * @return a future [[reactivemongo.core.commands.LastError]] that can be used to check whether the insertion was successful.
   */
  def insert(document: Structure)(implicit ec: ExecutionContext): Future[LastError] = insert(document, GetLastError())

  /**
   * Updates one or more documents matching the given selector with the given modifier or update object.
   *
   * @tparam S the type of the selector object. An implicit `Writer[S]` typeclass for handling it has to be in the scope.
   * @tparam U the type of the modifier or update object. An implicit `Writer[U]` typeclass for handling it has to be in the scope.
   *
   * @param selector the selector object, for finding the documents to update.
   * @param update the modifier object (with special keys like \$set) or replacement object.
   * @param writeConcern the [[reactivemongo.core.commands.GetLastError]] command message to send in order to control how the documents are updated. Defaults to GetLastError().
   * @param upsert states whether the update objet should be inserted if no match found. Defaults to false.
   * @param multi states whether the update may be done on all the matching documents.
   *
   * @return a future [[reactivemongo.core.commands.LastError]] that can be used to check whether the update was successful.
   */
  def update[S, U](selector: S, update: U, writeConcern: GetLastError = GetLastError(), upsert: Boolean = false, multi: Boolean = false)(implicit selectorWriter: Writer[S], updateWriter: Writer[U], ec: ExecutionContext): Future[LastError] = {
    val flags = 0 | (if (upsert) UpdateFlags.Upsert else 0) | (if (multi) UpdateFlags.MultiUpdate else 0)
    val op = Update(fullCollectionName, flags)
    val bson = writeDoc(selector, selectorWriter)
    bson.writeBytes(writeDoc(update, updateWriter))
    val checkedWriteRequest = CheckedWriteRequest(op, BufferSequence(bson), writeConcern)
    Failover(checkedWriteRequest, db.connection.mongosystem, failoverStrategy).future.mapEither(LastError.meaningful(_))
  }

  /**
   * Remove the matched document(s) from the collection and wait for the [[reactivemongo.core.commands.LastError]] result.
   *
   * Please read the documentation about [[reactivemongo.core.commands.GetLastError]] to know how to use it properly.
   *
   * @tparam T the type of the selector of documents to remove. An implicit `Writer[T]` typeclass for handling it has to be in the scope.
   *
   * @param query the selector of documents to remove.
   * @param writeConcern the [[reactivemongo.core.commands.GetLastError]] command message to send in order to control how the documents are removed. Defaults to GetLastError().
   * @param firstMatchOnly states whether only the first matched documents has to be removed from this collection.
   *
   * @return a future [[reactivemongo.core.commands.LastError]] that can be used to check whether the removal was successful.
   */
  def remove[T](query: T, writeConcern: GetLastError = GetLastError(), firstMatchOnly: Boolean = false)(implicit writer: Writer[T], ec: ExecutionContext): Future[LastError] = {
    val op = Delete(fullCollectionName, if (firstMatchOnly) 1 else 0)
    val bson = writeDoc(query, writer)
    val checkedWriteRequest = CheckedWriteRequest(op, BufferSequence(bson), writeConcern)
    Failover(checkedWriteRequest, db.connection.mongosystem, failoverStrategy).future.mapEither(LastError.meaningful(_))
  }

  def bulkInsert[T](enumerator: Enumerator[T], bulkSize: Int = bulk.MaxDocs, bulkByteSize: Int = bulk.MaxBulkSize)(implicit writer: Writer[T], ec: ExecutionContext): Future[Int] =
    enumerator |>>> bulkInsertIteratee(bulkSize, bulkByteSize)

  def bulkInsertIteratee[T](bulkSize: Int = bulk.MaxDocs, bulkByteSize: Int = bulk.MaxBulkSize)(implicit writer: Writer[T], ec: ExecutionContext): Iteratee[T, Int] =
    Enumeratee.map { doc: T => writeDoc(doc, writer) } &>> bulk.iteratee(this, bulkSize, bulkByteSize)

  /**
   * Remove the matched document(s) from the collection without writeConcern.
   *
   * Please note that you cannot be sure that the matched documents have been effectively removed and when (hence the Unit return type).
   *
   * @tparam T the type of the selector of documents to remove. An implicit `Writer[T]` typeclass for handling it has to be in the scope.
   *
   * @param query the selector of documents to remove.
   * @param firstMatchOnly states whether only the first matched documents has to be removed from this collection.
   */
  def uncheckedRemove[T](query: T, firstMatchOnly: Boolean = false)(implicit writer: Writer[T], ec: ExecutionContext): Unit = {
    val op = Delete(fullCollectionName, if (firstMatchOnly) 1 else 0)
    val bson = writeDoc(query, writer)
    val message = RequestMaker(op, BufferSequence(bson))
    db.connection.send(message)
  }

  /**
   * Updates one or more documents matching the given selector with the given modifier or update object.
   *
   * Please note that you cannot be sure that the matched documents have been effectively updated and when (hence the Unit return type).
   *
   * @tparam S the type of the selector object. An implicit `Writer[S]` typeclass for handling it has to be in the scope.
   * @tparam U the type of the modifier or update object. An implicit `Writer[U]` typeclass for handling it has to be in the scope.
   *
   * @param selector the selector object, for finding the documents to update.
   * @param update the modifier object (with special keys like \$set) or replacement object.
   * @param upsert states whether the update objet should be inserted if no match found. Defaults to false.
   * @param multi states whether the update may be done on all the matching documents.
   */
  def uncheckedUpdate[S, U](selector: S, update: U, upsert: Boolean = false, multi: Boolean = false)(implicit selectorWriter: Writer[S], updateWriter: Writer[U]): Unit = {
    val flags = 0 | (if (upsert) UpdateFlags.Upsert else 0) | (if (multi) UpdateFlags.MultiUpdate else 0)
    val op = Update(fullCollectionName, flags)
    val bson = writeDoc(selector, selectorWriter)
    bson.writeBytes(writeDoc(update, updateWriter))
    val message = RequestMaker(op, BufferSequence(bson))
    db.connection.send(message)
  }

  /**
   * Inserts a document into the collection without writeConcern.
   *
   * Please note that you cannot be sure that the document has been effectively written and when (hence the Unit return type).
   *
   * @tparam T the type of the document to insert. An implicit `Writer[T]` typeclass for handling it has to be in the scope.
   *
   * @param document the document to insert.
   */
  def uncheckedInsert[T](document: T)(implicit writer: Writer[T]): Unit = {
    val op = Insert(0, fullCollectionName)
    val bson = writeDoc(document, writer)
    val message = RequestMaker(op, BufferSequence(bson))
    db.connection.send(message)
  }
}

/**
 * A builder that helps to make a fine-tuned query to MongoDB.
 * 
 * When the query is ready, you can call `cursor` to get a [[Cursor]], or `one` if you want to retrieve just one document.
 * 
 */
trait GenericQueryBuilder[Structure, Reader[_], Writer[_]] extends GenericHandlers[Structure, Reader, Writer] {
  type Self <: GenericQueryBuilder[Structure, Reader, Writer]

  def queryOption: Option[Structure]
  def sortOption: Option[Structure]
  def projectionOption: Option[Structure]
  def hintOption: Option[Structure]
  def explainFlag: Boolean
  def snapshotFlag: Boolean
  def commentString: Option[String]
  def options: QueryOpts
  def failover: FailoverStrategy
  def collection: Collection

  def merge: Structure

  def structureReader: Reader[Structure]

  def copy(
    queryOption: Option[Structure] = queryOption,
    sortOption: Option[Structure] = sortOption,
    projectionOption: Option[Structure] = projectionOption,
    hintOption: Option[Structure] = hintOption,
    explainFlag: Boolean = explainFlag,
    snapshotFlag: Boolean = snapshotFlag,
    commentString: Option[String] = commentString,
    options: QueryOpts = options,
    failover: FailoverStrategy = failover): Self

  private def write(structure: Structure, buffer: ChannelBufferWritableBuffer = ChannelBufferWritableBuffer()): ChannelBufferWritableBuffer = {
    StructureBufferWriter.write(structure, buffer)
  }

  /**
   * Sends this query and gets a [[Cursor]] of instances of `T`.
   * 
   * An implicit `Reader[T]` must be present in the scope.
   */
  def cursor[T](implicit reader: Reader[T] = structureReader, ec: ExecutionContext): Cursor[T] = {
    val documents = BufferSequence {
      val buffer = write(merge, ChannelBufferWritableBuffer())
      projectionOption.map { projection =>
        write(projection, buffer)
      }.getOrElse(buffer).buffer
    }

    val op = Query(options.flagsN, collection.fullCollectionName, options.skipN, options.batchSizeN)
    val requestMaker = RequestMaker(op, documents)

    Cursor.flatten(Failover(requestMaker, collection.db.connection.mongosystem, failover).future.map { response =>
      val cursor = new DefaultCursor(response, collection.db.connection, op, documents, failover)(BufferReaderInstance(reader), ec)
      if ((options.flagsN & QueryFlags.TailableCursor) != 0)
        new TailableCursor(cursor)
      else cursor
    })
  }

  /**
   * Sends this query and gets a future `Option[T]`.
   * 
   * An implicit `Reader[T]` must be present in the scope.
   */
  def one[T](implicit reader: Reader[T], ec: ExecutionContext): Future[Option[T]] = copy(options = options.batchSize(1)).cursor(reader, ec).headOption

  /**
   * Sets the query (the selector document).
   *
   * @tparam Qry The type of the query. An implicit [[reactivemongo.bson.handlers.RawBSONWriter]][Qry] typeclass for handling it has to be in the scope.
   */
  def query[Qry](selector: Qry)(implicit writer: Writer[Qry]): Self = copy(queryOption = Some(
    StructureWriter(writer).write(selector)))

  /** Sets the query (the selector document). */
  def query(selector: Structure): Self = copy(queryOption = Some(selector))

  /** Sets the sorting document. */
  def sort(document: Structure): Self = copy(sortOption = Some(document))

  def options(options: QueryOpts): Self = copy(options = options)

  /* TODO /** Sets the sorting document. */
  def sort(sorters: (String, SortOrder)*): Self = copy(sortDoc = {
    if (sorters.size == 0)
      None
    else {
      val bson = BSONDocument(
        (for (sorter <- sorters) yield sorter._1 -> BSONInteger(
          sorter._2 match {
            case SortOrder.Ascending => 1
            case SortOrder.Descending => -1
          })).toStream)
      Some(bson)
    }
  })*/

  /**
   * Sets the projection document (for [[http://www.mongodb.org/display/DOCS/Retrieving+a+Subset+of+Fields retrieving only a subset of fields]]).
   *
   * @tparam Pjn The type of the projection. An implicit [[reactivemongo.bson.handlers.RawBSONWriter]][Pjn] typeclass for handling it has to be in the scope.
   */
  def projection[Pjn](p: Pjn)(implicit writer: Writer[Pjn]): Self = copy(projectionOption = Some(
    StructureWriter(writer).write(p)))

  def projection(p: Structure): Self = copy(projectionOption = Some(p))

  /** Sets the hint document (a document that declares the index MongoDB should use for this query). */
  def hint(document: Structure): Self = copy(hintOption = Some(document))

  /** Sets the hint document (a document that declares the index MongoDB should use for this query). */
  // TODO def hint(indexName: String): Self = copy(hintOption = Some(BSONDocument(indexName -> BSONInteger(1))))

  //TODO def explain(flag: Boolean = true) :QueryBuilder = copy(explainFlag=flag)

  /** Toggles [[http://www.mongodb.org/display/DOCS/How+to+do+Snapshotted+Queries+in+the+Mongo+Database snapshot mode]]. */
  def snapshot(flag: Boolean = true): Self = copy(snapshotFlag = flag)

  /** Adds a comment to this query, that may appear in the MongoDB logs. */
  def comment(message: String): Self = copy(commentString = Some(message))
}

/** A mixin that provides commands about this Collection itself. */
trait CollectionMetaCommands {
  self: Collection =>
  import reactivemongo.core.commands._
  import reactivemongo.api.indexes._

  /**
   * Creates this collection.
   *
   * The returned future will be completed with an error if this collection already exists.
   *
   * @param autoIndexId States if should automatically add an index on the _id field. By default, regular collections will have an indexed _id field, in contrast to capped collections.
   */
  def create(autoIndexId: Boolean = true)(implicit ec: ExecutionContext): Future[Boolean] = db.command(new CreateCollection(name, None, if (autoIndexId) None else Some(false)))

  /**
   * Creates this collection as a capped one.
   *
   * The returned future will be completed with an error if this collection already exists.
   *
   * @param autoIndexId States if should automatically add an index on the _id field. By default, capped collections will NOT have an indexed _id field, in contrast to regular collections.
   */
  def createCapped(size: Long, maxDocuments: Option[Int], autoIndexId: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = db.command(new CreateCollection(name, Some(CappedOptions(size, maxDocuments)), if (autoIndexId) Some(true) else None))

  /**
   * Drops this collection.
   *
   * The returned future will be completed with an error if this collection does not exist.
   */
  def drop()(implicit ec: ExecutionContext): Future[Boolean] = db.command(new Drop(name))

  /**
   * If this collection is capped, removes all the documents it contains.
   */
  def emptyCapped()(implicit ec: ExecutionContext): Future[Boolean] = db.command(new EmptyCapped(name))

  /**
   * Converts this collection to a capped one.
   *
   * @param size The size of this capped collection, in bytes.
   * @param maxDocuments The maximum number of documents this capped collection can contain.
   */
  def convertToCapped(size: Long, maxDocuments: Option[Int])(implicit ec: ExecutionContext): Future[Boolean] = db.command(new ConvertToCapped(name, CappedOptions(size, maxDocuments)))

  /**
   * Renames this collection.
   *
   * @param to The new name of this collection.
   * @param dropExisting If a collection of name `to` already exists, then drops that collection before renaming this one.
   */
  def rename(to: String, dropExisting: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = db.command(new RenameCollection(name, to, dropExisting))

  /**
   * Returns various information about this collection.
   */
  def stats()(implicit ec: ExecutionContext): Future[CollStatsResult] = db.command(new CollStats(name))

  /**
   * Returns various information about this collection.
   *
   * @param scale A scale factor (for example, to get all the sizes in kilobytes).
   */
  def stats(scale: Int)(implicit ec: ExecutionContext): Future[CollStatsResult] = db.command(new CollStats(name, Some(scale)))

  /** Returns an index manager for this collection. */
  def indexesManager(implicit ec: ExecutionContext) = new IndexesManager(self.db).onCollection(name)
}

object findable {
  import reactivemongo.bson._
  import reactivemongo.bson.DefaultBSONHandlers._
  import scala.concurrent.ExecutionContext.Implicits.global

  trait Account { val id: Int }
  case class ConcreteAccount(id: Int) extends Account

  implicit object AccountWriter extends BSONDocumentWriter[ConcreteAccount] {
    def write(account: ConcreteAccount) = BSONDocument("id" -> BSONInteger(account.id))
  }

  implicit object AccountReader extends BSONDocumentReader[ConcreteAccount] {
    def read(bson: BSONDocument) = ConcreteAccount(bson.getAs[Int]("id").get)
  }

  val tt = new collections.default.BSONCollection(null, null, null).find(ConcreteAccount(6), ConcreteAccount(6)).cursor[ConcreteAccount]
  //val gg = tt.cursor[ConcreteAccount]
}

object test {
  implicit class DBWrapper(db: DB) {
    def collection[C <: Collection](name: String, failoverStrategy: FailoverStrategy)(implicit producer: CollectionProducer[C] = collections.default.BSONCollectionProducer): C = {
      producer.apply(db, name, failoverStrategy)
    }
  }
  import collections.default._
  import reactivemongo.bson._
  import reactivemongo.bson.DefaultBSONHandlers._

  import scala.concurrent.ExecutionContext.Implicits.global

  val connection = MongoConnection(List("localhost"))
  val db = connection("app")
  val coll = db.collection("articles", FailoverStrategy())

  val list = coll.find(BSONDocument(), BSONDocument()).cursor.toList

  list.onComplete {
    case Failure(e) =>
      e.printStackTrace
    case Success(list) =>
      println(s"got list of size ${list.size}")
      list.foreach { doc =>
        println(BSONDocument.pretty(doc))
      }
  }
}