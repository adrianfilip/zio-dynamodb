package zio.dynamodb

import zio.duration._
import zio.dynamodb.DynamoDBQuery.BatchGetItem.TableGet
import zio.dynamodb.DynamoDBQuery.BatchWriteItem.{ Delete, Put }
import zio.dynamodb.DynamoDBQuery.{
  batched,
  parallelize,
  BatchGetItem,
  BatchWriteItem,
  CreateTable,
  DeleteItem,
  GetItem,
  Map,
  PutItem,
  QueryAll,
  QuerySome,
  ScanAll,
  ScanSome,
  UpdateItem,
  Zip
}
import zio.dynamodb.UpdateExpression.Action
import zio.schema.Schema
import zio.stream.Stream
import zio.{ Chunk, Has, Schedule, ZIO }

sealed trait DynamoDBQuery[+A] { self =>

  final def <*[B](that: DynamoDBQuery[B]): DynamoDBQuery[A] = zipLeft(that)

  final def *>[B](that: DynamoDBQuery[B]): DynamoDBQuery[B] = zipRight(that)

  final def <*>[B](that: DynamoDBQuery[B]): DynamoDBQuery[(A, B)] = self zip that

  def execute: ZIO[Has[DynamoDBExecutor], Throwable, A] = {
    val (constructors, assembler)                                                                   = parallelize(self)
    val (indexedConstructors, (batchGetItem, batchGetIndexes), (batchWriteItem, batchWriteIndexes)) =
      batched(constructors)

    val indexedNonBatchedResults =
      ZIO.foreachPar(indexedConstructors) {
        case (constructor, index) =>
          ddbExecute(constructor).map(result => (result, index))
      }

    val indexedGetResults =
      ddbExecute(batchGetItem).map(resp => batchGetItem.toGetItemResponses(resp) zip batchGetIndexes)

    val indexedWriteResults =
      ddbExecute(batchWriteItem).as(batchWriteItem.addList.map(_ => ()) zip batchWriteIndexes)

    (indexedNonBatchedResults zipPar indexedGetResults zipPar indexedWriteResults).map {
      case ((nonBatched, batchedGets), batchedWrites) =>
        val combined = (nonBatched ++ batchedGets ++ batchedWrites).sortBy {
          case (_, index) => index
        }.map { case (value, _) => value }
        assembler(combined)
    }

  }

  final def indexName(indexName: String): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.indexName(indexName), right.indexName(indexName), zippable)
      case Map(query, mapper)         => Map(query.indexName(indexName), mapper)
      case q: ScanAll                 =>
        q.copy(indexName = Some(IndexName(indexName))).asInstanceOf[DynamoDBQuery[A]]
      case q: ScanSome                =>
        q.copy(indexName = Some(IndexName(indexName))).asInstanceOf[DynamoDBQuery[A]]
      case q: QueryAll                =>
        q.copy(indexName = Some(IndexName(indexName))).asInstanceOf[DynamoDBQuery[A]]
      case q: QuerySome               =>
        q.copy(indexName = Some(IndexName(indexName))).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  final def capacity(capacity: ReturnConsumedCapacity): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.capacity(capacity), right.capacity(capacity), zippable)
      case Map(query, mapper)         => Map(query.capacity(capacity), mapper)
      case g: GetItem                 =>
        g.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case b: BatchGetItem            =>
        b.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case b: BatchWriteItem          =>
        b.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case q: ScanAll                 =>
        q.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case q: ScanSome                =>
        q.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case q: QueryAll                =>
        q.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case q: QuerySome               =>
        q.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case m: PutItem                 =>
        m.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case m: UpdateItem              =>
        m.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case m: DeleteItem              =>
        m.copy(capacity = capacity).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  final def consistency(consistency: ConsistencyMode): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.consistency(consistency), right.consistency(consistency), zippable)
      case Map(query, mapper)         => Map(query.consistency(consistency), mapper)
      case g: GetItem                 =>
        g.copy(consistency = consistency).asInstanceOf[DynamoDBQuery[A]]
      case q: ScanAll                 =>
        q.copy(consistency = consistency).asInstanceOf[DynamoDBQuery[A]]
      case q: ScanSome                =>
        q.copy(consistency = consistency).asInstanceOf[DynamoDBQuery[A]]
      case q: QueryAll                =>
        q.copy(consistency = consistency).asInstanceOf[DynamoDBQuery[A]]
      case q: QuerySome               =>
        q.copy(consistency = consistency).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def returns(returnValues: ReturnValues): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.returns(returnValues), right.returns(returnValues), zippable)
      case Map(query, mapper)         => Map(query.returns(returnValues), mapper)
      case p: PutItem                 =>
        p.copy(returnValues = returnValues).asInstanceOf[DynamoDBQuery[A]]
      case u: UpdateItem              =>
        u.copy(returnValues = returnValues).asInstanceOf[DynamoDBQuery[A]]
      case d: DeleteItem              =>
        d.copy(returnValues = returnValues).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def where(conditionExpression: ConditionExpression): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(left.where(conditionExpression), right.where(conditionExpression), zippable)
      case Map(query, mapper)         => Map(query.where(conditionExpression), mapper)
      case p: PutItem                 =>
        p.copy(conditionExpression = Some(conditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case u: UpdateItem              =>
        u.copy(conditionExpression = Some(conditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case d: DeleteItem              =>
        d.copy(conditionExpression = Some(conditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def metrics(itemMetrics: ReturnItemCollectionMetrics): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.metrics(itemMetrics), right.metrics(itemMetrics), zippable)
      case Map(query, mapper)         => Map(query.metrics(itemMetrics), mapper)
      case p: PutItem                 =>
        p.copy(itemMetrics = itemMetrics).asInstanceOf[DynamoDBQuery[A]]
      case u: UpdateItem              =>
        u.copy(itemMetrics = itemMetrics).asInstanceOf[DynamoDBQuery[A]]
      case d: DeleteItem              =>
        d.copy(itemMetrics = itemMetrics).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def startKey(exclusiveStartKey: LastEvaluatedKey): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(left.startKey(exclusiveStartKey), right.startKey(exclusiveStartKey), zippable)
      case Map(query, mapper)         => Map(query.startKey(exclusiveStartKey), mapper)
      case s: ScanSome                => s.copy(exclusiveStartKey = exclusiveStartKey).asInstanceOf[DynamoDBQuery[A]]
      case s: ScanAll                 => s.copy(exclusiveStartKey = exclusiveStartKey).asInstanceOf[DynamoDBQuery[A]]
      case s: QuerySome               => s.copy(exclusiveStartKey = exclusiveStartKey).asInstanceOf[DynamoDBQuery[A]]
      case s: QueryAll                => s.copy(exclusiveStartKey = exclusiveStartKey).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def filter(filterExpression: FilterExpression): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.filter(filterExpression), right.filter(filterExpression), zippable)
      case Map(query, mapper)         => Map(query.filter(filterExpression), mapper)
      case s: ScanSome                => s.copy(filterExpression = Some(filterExpression)).asInstanceOf[DynamoDBQuery[A]]
      case s: ScanAll                 => s.copy(filterExpression = Some(filterExpression)).asInstanceOf[DynamoDBQuery[A]]
      case s: QuerySome               => s.copy(filterExpression = Some(filterExpression)).asInstanceOf[DynamoDBQuery[A]]
      case s: QueryAll                => s.copy(filterExpression = Some(filterExpression)).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  /**
   * Parallel executes DynamoDB queries in parallel if the query type has parallel features in DynamoDB.
   * There are no guarantees on order of returned items.
   *
   * @param n The number of parallel requests to make to DynamoDB
   */

  def parallel(n: Int): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.parallel(n), right.parallel(n), zippable)
      case Map(query, mapper)         => Map(query.parallel(n), mapper)
      case s: ScanAll                 => s.copy(totalSegments = n).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def gsi(
    indexName: String,
    keySchema: KeySchema,
    projection: ProjectionType,
    readCapacityUnit: Long,
    writeCapacityUnit: Long
  ): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(
          left.gsi(indexName, keySchema, projection, readCapacityUnit, writeCapacityUnit),
          right.gsi(indexName, keySchema, projection, readCapacityUnit, writeCapacityUnit),
          zippable
        )
      case Map(query, mapper)         =>
        Map(query.gsi(indexName, keySchema, projection, readCapacityUnit, writeCapacityUnit), mapper)
      case s: CreateTable             =>
        s.copy(globalSecondaryIndexes =
          s.globalSecondaryIndexes + GlobalSecondaryIndex(
            indexName,
            keySchema,
            projection,
            Some(ProvisionedThroughput(readCapacityUnit, writeCapacityUnit))
          )
        ).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def gsi(
    indexName: String,
    keySchema: KeySchema,
    projection: ProjectionType
  ): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(
          left.gsi(indexName, keySchema, projection),
          right.gsi(indexName, keySchema, projection),
          zippable
        )
      case Map(query, mapper)         => Map(query.gsi(indexName, keySchema, projection), mapper)
      case s: CreateTable             =>
        s.copy(globalSecondaryIndexes =
          s.globalSecondaryIndexes + GlobalSecondaryIndex(
            indexName,
            keySchema,
            projection,
            None
          )
        ).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def lsi(
    indexName: String,
    keySchema: KeySchema,
    projection: ProjectionType = ProjectionType.All
  ): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(left.lsi(indexName, keySchema, projection), right.lsi(indexName, keySchema, projection), zippable)
      case Map(query, mapper)         => Map(query.lsi(indexName, keySchema, projection), mapper)
      case s: CreateTable             =>
        s.copy(localSecondaryIndexes =
          s.localSecondaryIndexes + LocalSecondaryIndex(
            indexName,
            keySchema,
            projection
          )
        ).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def selectAllAttributes: DynamoDBQuery[A]          = select(Select.AllAttributes)
  def selectAllProjectedAttributes: DynamoDBQuery[A] = select(Select.AllProjectedAttributes)
  def selectSpecificAttributes: DynamoDBQuery[A]     = select(Select.SpecificAttributes)
  def selectCount: DynamoDBQuery[A]                  = select(Select.Count)

  /**
   * Adds a KeyConditionExpression to a DynamoDBQuery. Example:
   * {{{
   * val newQuery = query.whereKey(partitionKey("email") === "avi@gmail.com" && sortKey("subject") === "maths")
   * }}}
   */
  def whereKey(keyConditionExpression: KeyConditionExpression): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(left.whereKey(keyConditionExpression), right.whereKey(keyConditionExpression), zippable)
      case Map(query, mapper)         => Map(query.whereKey(keyConditionExpression), mapper)
      case s: QuerySome               => s.copy(keyConditionExpression = Some(keyConditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case s: QueryAll                => s.copy(keyConditionExpression = Some(keyConditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  /**
   * Adds a KeyConditionExpression from a ConditionExpression to a DynamoDBQuery
   * Must be in the form of `<Condition1> && <Condition2>` where format of `<Condition1>` is:
   * {{{<ProjectionExpressionForPartitionKey> === <value>}}}
   * and the format of `<Condition2>` is:
   * {{{<ProjectionExpressionForSortKey> <op> <value>}}} where op can be one of `===`, `>`, `>=`, `<`, `<=`, `between`, `beginsWith`
   *
   * Example using type safe API:
   * {{{
   * // email and subject are partition and sort keys respectively
   * val (email, subject, enrollmentDate, payment) = ProjectionExpression.accessors[Student]
   * // ...
   * val newQuery = query.whereKey(email === "avi@gmail.com" && subject === "maths")
   * }}}
   */
  def whereKey(conditionExpression: ConditionExpression): DynamoDBQuery[A] = {
    val keyConditionExpression: KeyConditionExpression =
      KeyConditionExpression.fromConditionExpressionUnsafe(conditionExpression)
    self match {
      case Zip(left, right, zippable) =>
        Zip(left.whereKey(keyConditionExpression), right.whereKey(keyConditionExpression), zippable)
      case Map(query, mapper)         => Map(query.whereKey(keyConditionExpression), mapper)
      case s: QuerySome               => s.copy(keyConditionExpression = Some(keyConditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case s: QueryAll                => s.copy(keyConditionExpression = Some(keyConditionExpression)).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }
  }

  def withRetryPolicy(retryPolicy: Schedule[Any, Throwable, Any]): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) =>
        Zip(left.withRetryPolicy(retryPolicy), right.withRetryPolicy(retryPolicy), zippable)
      case Map(query, mapper)         => Map(query.withRetryPolicy(retryPolicy), mapper)
      case s: BatchWriteItem          => s.copy(retryPolicy = retryPolicy).asInstanceOf[DynamoDBQuery[A]]
      case s: BatchGetItem            => s.copy(retryPolicy = retryPolicy).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  def sortOrder(ascending: Boolean): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.sortOrder(ascending), right.sortOrder(ascending), zippable)
      case Map(query, mapper)         => Map(query.sortOrder(ascending), mapper)
      case s: QuerySome               => s.copy(ascending = ascending).asInstanceOf[DynamoDBQuery[A]]
      case s: QueryAll                => s.copy(ascending = ascending).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }

  final def map[B](f: A => B): DynamoDBQuery[B] = DynamoDBQuery.Map(self, f)

  final def zip[B](that: DynamoDBQuery[B])(implicit z: Zippable[A, B]): DynamoDBQuery[z.Out] =
    DynamoDBQuery.Zip[A, B, z.Out](self, that, z)

  final def zipLeft[B](that: DynamoDBQuery[B]): DynamoDBQuery[A] = (self zip that).map(_._1)

  final def zipRight[B](that: DynamoDBQuery[B]): DynamoDBQuery[B] = (self zip that).map(_._2)

  final def zipWith[B, C](that: DynamoDBQuery[B])(f: (A, B) => C): DynamoDBQuery[C] =
    self.zip(that).map(f.tupled)

  private def select(select: Select): DynamoDBQuery[A] =
    self match {
      case Zip(left, right, zippable) => Zip(left.select(select), right.select(select), zippable)
      case Map(query, mapper)         => Map(query.select(select), mapper)
      case s: ScanSome                => s.copy(select = Some(select)).asInstanceOf[DynamoDBQuery[A]]
      case s: ScanAll                 => s.copy(select = Some(select)).asInstanceOf[DynamoDBQuery[A]]
      case s: QuerySome               => s.copy(select = Some(select)).asInstanceOf[DynamoDBQuery[A]]
      case s: QueryAll                => s.copy(select = Some(select)).asInstanceOf[DynamoDBQuery[A]]
      case _                          => self
    }
}

object DynamoDBQuery {
  import scala.collection.immutable.{ Map => ScalaMap }
  import scala.collection.immutable.{ Set => ScalaSet }

  sealed trait Constructor[+A] extends DynamoDBQuery[A]
  sealed trait Write[+A]       extends Constructor[A]

  def succeed[A](a: A): DynamoDBQuery[A] = Succeed(() => a)

  /**
   * Each element in `values` is zipped together using function `body` which has signature `A => DynamoDBQuery[B]`
   * Note that when `DynamoDBQuery`'s are zipped together, on execution the queries are batched together as AWS DynamoDB
   * batch queries whenever this is possible.
   *
   * Note this is a low level function for a small amount of elements - if you want to perform a large number of reads
   * and writes prefer the following utility functions - [[zio.dynamodb.batchReadItemFromStream]],
   * [[zio.dynamodb.batchWriteFromStream]] which work with ZStreams and efficiently limit batch sizes to the maximum size
   * allowed by the AWS API.
   */
  def forEach[A, B](values: Iterable[A])(body: A => DynamoDBQuery[B]): DynamoDBQuery[List[B]] =
    values.foldRight[DynamoDBQuery[List[B]]](succeed(Nil)) {
      case (a, query) => body(a).zipWith(query)(_ :: _)
    }

  def getItem(
    tableName: String,
    key: PrimaryKey,
    projections: ProjectionExpression*
  ): DynamoDBQuery[Option[Item]] =
    GetItem(TableName(tableName), key, projections.toList)

  def get[A: Schema](
    tableName: String,
    key: PrimaryKey,
    projections: ProjectionExpression*
  ): DynamoDBQuery[Either[String, A]] =
    getItem(tableName, key, projections: _*).map {
      case Some(item) =>
        fromItem(item)
      case None       => Left(s"value with key $key not found")
    }

  private[dynamodb] def fromItem[A: Schema](item: Item): Either[String, A] = {
    val av = ToAttributeValue.attrMapToAttributeValue.toAttributeValue(item)
    av.decode(Schema[A])
  }

  def putItem(tableName: String, item: Item): DynamoDBQuery[Unit] = PutItem(TableName(tableName), item)

  def put[A: Schema](tableName: String, a: A): DynamoDBQuery[Unit] =
    putItem(tableName, toItem(a))

  private[dynamodb] def toItem[A](a: A)(implicit schema: Schema[A]): Item =
    FromAttributeValue.attrMapFromAttributeValue
      .fromAttributeValue(AttributeValue.encode(a)(schema))
      .getOrElse(throw new Exception(s"error encoding $a"))

  def updateItem(tableName: String, key: PrimaryKey)(action: Action): DynamoDBQuery[Option[Item]] =
    UpdateItem(TableName(tableName), key, UpdateExpression(action))

  def deleteItem(tableName: String, key: PrimaryKey): Write[Unit] = DeleteItem(TableName(tableName), key)

  /**
   * when executed will return a Tuple of {{{(Chunk[Item], LastEvaluatedKey)}}}
   */
  def scanSomeItem(tableName: String, limit: Int, projections: ProjectionExpression*): ScanSome =
    ScanSome(
      TableName(tableName),
      limit,
      select = selectOrAll(projections),
      projections = projections.toList
    )

  /**
   * when executed will return a Tuple of {{{Either[String,(Chunk[A], LastEvaluatedKey)]}}}
   */
  def scanSome[A: Schema](
    tableName: String,
    limit: Int,
    projections: ProjectionExpression*
  ): DynamoDBQuery[Either[String, (Chunk[A], LastEvaluatedKey)]] =
    scanSomeItem(tableName, limit, projections: _*).map {
      case (itemsChunk, lek) =>
        EitherUtil.forEach(itemsChunk)(item => fromItem(item)).map(Chunk.fromIterable) match {
          case Right(chunk) => Right((chunk, lek))
          case Left(error)  => Left(error)
        }
    }

  /**
   * when executed will return a ZStream of Item
   */
  def scanAllItem(tableName: String, projections: ProjectionExpression*): ScanAll =
    ScanAll(
      TableName(tableName),
      select = selectOrAll(projections),
      projections = projections.toList
    )

  /**
   * when executed will return a ZStream of A
   */
  def scanAll[A: Schema](
    tableName: String,
    projections: ProjectionExpression*
  ): DynamoDBQuery[Stream[Throwable, A]] =
    scanAllItem(tableName, projections: _*).map(
      _.mapM(item => ZIO.fromEither(fromItem(item)).mapError(new IllegalStateException(_)))
    ) // TODO: think about error model

  /**
   * when executed will return a Tuple of {{{(Chunk[Item], LastEvaluatedKey)}}}
   */
  def querySomeItem(tableName: String, limit: Int, projections: ProjectionExpression*): QuerySome =
    QuerySome(
      TableName(tableName),
      limit,
      select = selectOrAll(projections),
      projections = projections.toList
    )

  /**
   * when executed will return a Tuple of {{{Either[String,(Chunk[A], LastEvaluatedKey)]}}}
   */
  def querySome[A: Schema](
    tableName: String,
    limit: Int,
    projections: ProjectionExpression*
  ): DynamoDBQuery[Either[String, (Chunk[A], LastEvaluatedKey)]] =
    querySomeItem(tableName, limit, projections: _*).map {
      case (itemsChunk, lek) =>
        EitherUtil.forEach(itemsChunk)(item => fromItem(item)).map(Chunk.fromIterable) match {
          case Right(chunk) => Right((chunk, lek))
          case Left(error)  => Left(error)
        }
    }

  /**
   * when executed will return a ZStream of Item
   */
  def queryAllItem(tableName: String, projections: ProjectionExpression*): QueryAll =
    QueryAll(
      TableName(tableName),
      select = selectOrAll(projections),
      projections = projections.toList
    )

  /**
   * when executed will return a ZStream of A
   */
  def queryAll[A: Schema](
    tableName: String,
    //keyConditionExpression: KeyConditionExpression, REVIEW: This is required by the dynamo API, should we make it required here?
    projections: ProjectionExpression*
  ): DynamoDBQuery[Stream[Throwable, A]] =
    queryAllItem(tableName, projections: _*).map(
      _.mapM(item => ZIO.fromEither(fromItem(item)).mapError(new IllegalStateException(_)))
    ) // TODO: think about error model

  def createTable(
    tableName: String,
    keySchema: KeySchema,
    billingMode: BillingMode,
    sseSpecification: Option[SSESpecification] = None,
    tags: ScalaMap[String, String] = ScalaMap.empty
  )(attributeDefinition: AttributeDefinition, attributeDefinitions: AttributeDefinition*): CreateTable =
    CreateTable(
      tableName = TableName(tableName),
      keySchema = keySchema,
      attributeDefinitions = NonEmptySet(attributeDefinition, attributeDefinitions: _*),
      billingMode = billingMode,
      sseSpecification = sseSpecification,
      tags = tags
    )

  def deleteTable(
    tableName: String
  ): DeleteTable = DeleteTable(tableName = TableName(tableName))

  def describeTable(
    tableName: String
  ): DescribeTable = DescribeTable(tableName = TableName(tableName))

  private def selectOrAll(projections: Seq[ProjectionExpression]): Option[Select] =
    Some(if (projections.isEmpty) Select.AllAttributes else Select.SpecificAttributes)

  private[dynamodb] final case class Succeed[A](value: () => A) extends Constructor[A]

  private[dynamodb] final case class GetItem(
    tableName: TableName,
    key: PrimaryKey,
    projections: List[ProjectionExpression] =
      List.empty, // If no attribute names are specified, then all attributes are returned
    consistency: ConsistencyMode = ConsistencyMode.Weak,
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None
  ) extends Constructor[Option[Item]]

  private[dynamodb] final case class BatchRetryError() extends Throwable

  private[dynamodb] final case class BatchGetItem(
    requestItems: ScalaMap[TableName, BatchGetItem.TableGet] = ScalaMap.empty,
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    private[dynamodb] val orderedGetItems: Chunk[GetItem] =
      Chunk.empty, // track order of added GetItems for later unpacking
    retryPolicy: Schedule[Any, Throwable, Any] = Schedule.recurs(5) && Schedule.exponential(30.seconds)
  ) extends Constructor[BatchGetItem.Response] { self =>

    def +(getItem: GetItem): BatchGetItem = {
      val tableName                                               = getItem.tableName
      val key                                                     = getItem.key
      val projectionExpressionSet: ScalaSet[ProjectionExpression] = getItem.projections.toSet
      val newEntry: (TableName, TableGet)                         =
        self.requestItems
          .get(tableName)
          .fold((tableName, BatchGetItem.TableGet(ScalaSet(key), getItem.projections.toSet)))(t =>
            (
              tableName,
              BatchGetItem.TableGet(t.keysSet + key, t.projectionExpressionSet ++ projectionExpressionSet)
            )
          )
      BatchGetItem(
        self.requestItems + newEntry,
        self.capacity,
        self.orderedGetItems :+ getItem
      )
    }

    def addAll(entries: GetItem*): BatchGetItem =
      entries.foldLeft(self) {
        case (batch, getItem) => batch + getItem
      }

    /*
     for each added GetItem, check it's key exists in the response and create a corresponding Optional Item value
     */
    def toGetItemResponses(response: BatchGetItem.Response): Chunk[Option[Item]] = {
      val chunk: Chunk[Option[Item]] = orderedGetItems.foldLeft[Chunk[Option[Item]]](Chunk.empty) {
        case (chunk, getItem) =>
          val responsesForTable: Set[Item] = response.responses.getOrElse(getItem.tableName, Set.empty[Item])
          // What if the projection expression for responsesForTable doesn't include the primaryKey?
          // Shouldn't the responseForTable have only the requested item?
          val found: Option[Item]          = responsesForTable.find { item =>
            getItem.key.map.toSet.subsetOf(item.map.toSet)
          }
          found.fold(chunk :+ None)(item => chunk :+ Some(item))
      }

      chunk
    }

  }
  private[dynamodb] object BatchGetItem {
    final case class TableGet(
      keysSet: ScalaSet[PrimaryKey],
      projectionExpressionSet: ScalaSet[ProjectionExpression]
    )
    final case class Response(
      // Note - if a requested item does not exist, it is not returned in the result
      responses: MapOfSet[TableName, Item] = MapOfSet.empty,
      unprocessedKeys: ScalaMap[TableName, TableGet] = ScalaMap.empty
    )
  }

  private[dynamodb] final case class BatchWriteItem(
    requestItems: MapOfSet[TableName, BatchWriteItem.Write] = MapOfSet.empty,
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    itemMetrics: ReturnItemCollectionMetrics = ReturnItemCollectionMetrics.None,
    addList: Chunk[BatchWriteItem.Write] = Chunk.empty,
    retryPolicy: Schedule[Any, Throwable, Any] =
      Schedule.recurs(5) && Schedule.exponential(30.seconds)
  ) extends Constructor[BatchWriteItem.Response] { self =>
    def +[A](writeItem: Write[A]): BatchWriteItem =
      writeItem match {
        case putItem @ PutItem(_, _, _, _, _, _)       =>
          BatchWriteItem(
            self.requestItems + ((putItem.tableName, Put(putItem.item))),
            self.capacity,
            self.itemMetrics,
            self.addList :+ Put(putItem.item),
            self.retryPolicy
          )
        case deleteItem @ DeleteItem(_, _, _, _, _, _) =>
          BatchWriteItem(
            self.requestItems + ((deleteItem.tableName, Delete(deleteItem.key))),
            self.capacity,
            self.itemMetrics,
            self.addList :+ Delete(deleteItem.key),
            self.retryPolicy
          )
      }

    def addAll[A](entries: Write[A]*): BatchWriteItem =
      entries.foldLeft(self) {
        case (batch, write) => batch + write
      }
  }

  private[dynamodb] object BatchWriteItem {
    sealed trait Write
    final case class Delete(key: PrimaryKey) extends Write
    final case class Put(item: Item)         extends Write

    final case class Response(
      unprocessedItems: Option[MapOfSet[TableName, BatchWriteItem.Write]]
    )

  }
  private[dynamodb] final case class DeleteTable(
    tableName: TableName
  ) extends Constructor[Unit]

  private[dynamodb] final case class DescribeTable(
    tableName: TableName
  ) extends Constructor[DescribeTableResponse]

  sealed trait TableStatus
  object TableStatus {
    case object Creating                          extends TableStatus
    case object Updating                          extends TableStatus
    case object Deleting                          extends TableStatus
    case object Active                            extends TableStatus
    case object InaccessibleEncryptionCredentials extends TableStatus
    case object Archiving                         extends TableStatus
    case object Archived                          extends TableStatus
    case object unknownToSdkVersion               extends TableStatus
  }

  // TODO(adam): Add more fields here, this was for some basic testing initially
  final case class DescribeTableResponse(
    tableArn: String,
    tableStatus: TableStatus
  )

  // Interestingly scan can be run in parallel using segment number and total segments fields
  // If running in parallel segment number must be used consistently with the paging token
  // I have removed these fields on the assumption that the library will take care of these concerns
  private[dynamodb] final case class ScanSome(
    tableName: TableName,
    limit: Int,                                           // TODO: should this be a long to match AWS API?
    indexName: Option[IndexName] = None,
    consistency: ConsistencyMode = ConsistencyMode.Weak,
    exclusiveStartKey: LastEvaluatedKey =
      None,                                               // allows client to control start position - eg for client managed paging
    filterExpression: Option[FilterExpression] = None,
    projections: List[ProjectionExpression] = List.empty, // if empty all attributes will be returned
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    select: Option[Select] = None                         // if ProjectExpression supplied then only valid value is SpecificAttributes
  ) extends Constructor[(Chunk[Item], LastEvaluatedKey)]

  private[dynamodb] final case class QuerySome(
    tableName: TableName,
    limit: Int,                                           // TODO: should this be a long to match AWS API?
    indexName: Option[IndexName] = None,
    consistency: ConsistencyMode = ConsistencyMode.Weak,
    exclusiveStartKey: LastEvaluatedKey =
      None,                                               // allows client to control start position - eg for client managed paging
    filterExpression: Option[FilterExpression] = None,
    keyConditionExpression: Option[KeyConditionExpression] = None,
    projections: List[ProjectionExpression] = List.empty, // if empty all attributes will be returned
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    select: Option[Select] = None,                        // if ProjectExpression supplied then only valid value is SpecificAttributes
    ascending: Boolean = true
  ) extends Constructor[(Chunk[Item], LastEvaluatedKey)]

  private[dynamodb] final case class ScanAll(
    tableName: TableName,
    indexName: Option[IndexName] = None,
    limit: Option[Int] = None,
    consistency: ConsistencyMode = ConsistencyMode.Weak,
    exclusiveStartKey: LastEvaluatedKey =
      None,                                               // allows client to control start position - eg for client managed paging
    filterExpression: Option[FilterExpression] = None,
    projections: List[ProjectionExpression] = List.empty, // if empty all attributes will be returned
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    select: Option[Select] = None,                        // if ProjectExpression supplied then only valid value is SpecificAttributes
    totalSegments: Int = 1
  ) extends Constructor[Stream[Throwable, Item]]

  object ScanAll {
    final case class Segment(number: Int, total: Int)
  }

  private[dynamodb] final case class QueryAll(
    tableName: TableName,
    indexName: Option[IndexName] = None,
    limit: Option[Int] = None,
    consistency: ConsistencyMode = ConsistencyMode.Weak,
    exclusiveStartKey: LastEvaluatedKey =
      None,                                               // allows client to control start position - eg for client managed paging
    filterExpression: Option[FilterExpression] = None,
    keyConditionExpression: Option[KeyConditionExpression] = None,
    projections: List[ProjectionExpression] = List.empty, // if empty all attributes will be returned
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    select: Option[Select] = None,                        // if ProjectExpression supplied then only valid value is SpecificAttributes
    ascending: Boolean = true
  ) extends Constructor[Stream[Throwable, Item]]

  private[dynamodb] final case class PutItem(
    tableName: TableName,
    item: Item,
    conditionExpression: Option[ConditionExpression] = None,
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    itemMetrics: ReturnItemCollectionMetrics = ReturnItemCollectionMetrics.None,
    returnValues: ReturnValues = ReturnValues.None // PutItem does not recognize any values other than NONE or ALL_OLD.
  ) extends Write[Unit]

  private[dynamodb] final case class UpdateItem(
    tableName: TableName,
    key: PrimaryKey,
    updateExpression: UpdateExpression,
    conditionExpression: Option[ConditionExpression] = None,
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    itemMetrics: ReturnItemCollectionMetrics = ReturnItemCollectionMetrics.None,
    returnValues: ReturnValues = ReturnValues.None
  ) extends Constructor[Option[Item]]

  private[dynamodb] final case class DeleteItem(
    tableName: TableName,
    key: PrimaryKey,
    conditionExpression: Option[ConditionExpression] = None,
    capacity: ReturnConsumedCapacity = ReturnConsumedCapacity.None,
    itemMetrics: ReturnItemCollectionMetrics = ReturnItemCollectionMetrics.None,
    returnValues: ReturnValues =
      ReturnValues.None // DeleteItem does not recognize any values other than NONE or ALL_OLD.
  ) extends Write[Unit]

  private[dynamodb] final case class CreateTable(
    tableName: TableName,
    keySchema: KeySchema,
    attributeDefinitions: NonEmptySet[AttributeDefinition],
    billingMode: BillingMode,
    globalSecondaryIndexes: Set[GlobalSecondaryIndex] = Set.empty,
    localSecondaryIndexes: Set[LocalSecondaryIndex] = Set.empty,
    sseSpecification: Option[SSESpecification] = None,
    tags: ScalaMap[String, String] = ScalaMap.empty // you can have up to 50 tags
  ) extends Constructor[Unit]

  private[dynamodb] final case class Zip[A, B, C](
    left: DynamoDBQuery[A],
    right: DynamoDBQuery[B],
    zippable: Zippable.Out[A, B, C]
  )                                                                                     extends DynamoDBQuery[C]
  private[dynamodb] final case class Map[A, B](query: DynamoDBQuery[A], mapper: A => B) extends DynamoDBQuery[B]

  def apply[A](a: => A): DynamoDBQuery[A] = Succeed(() => a)

  private[dynamodb] def batched(
    constructors: Chunk[Constructor[Any]]
  ): (Chunk[(Constructor[Any], Int)], (BatchGetItem, Chunk[Int]), (BatchWriteItem, Chunk[Int])) = {
    type IndexedConstructor = (Constructor[Any], Int)
    type IndexedGetItem     = (GetItem, Int)
    type IndexedWriteItem   = (Write[Unit], Int)

    val (nonBatched, gets, writes) =
      constructors.zipWithIndex.foldLeft[(Chunk[IndexedConstructor], Chunk[IndexedGetItem], Chunk[IndexedWriteItem])](
        (Chunk.empty, Chunk.empty, Chunk.empty)
      ) {
        case ((nonBatched, gets, writes), (get @ GetItem(_, _, _, _, _), index))          =>
          (nonBatched, gets :+ (get -> index), writes)
        case ((nonBatched, gets, writes), (put @ PutItem(_, _, _, _, _, _), index))       =>
          (nonBatched, gets, writes :+ (put -> index))
        case ((nonBatched, gets, writes), (delete @ DeleteItem(_, _, _, _, _, _), index)) =>
          (nonBatched, gets, writes :+ (delete -> index))
        case ((nonBatched, gets, writes), (nonGetItem, index))                            =>
          (nonBatched :+ (nonGetItem -> index), gets, writes)
      }

    val indexedBatchGetItem: (BatchGetItem, Chunk[Int]) = gets
      .foldLeft[(BatchGetItem, Chunk[Int])]((BatchGetItem(), Chunk.empty)) {
        case ((batchGetItem, indexes), (getItem, index)) => (batchGetItem + getItem, indexes :+ index)
      }

    val indexedBatchWrite: (BatchWriteItem, Chunk[Int]) = writes
      .foldLeft[(BatchWriteItem, Chunk[Int])]((BatchWriteItem(), Chunk.empty)) {
        case ((batchWriteItem, indexes), (writeItem, index)) => (batchWriteItem + writeItem, indexes :+ index)
      }

    (nonBatched, indexedBatchGetItem, indexedBatchWrite)
  }

  private[dynamodb] def parallelize[A](query: DynamoDBQuery[A]): (Chunk[Constructor[Any]], Chunk[Any] => A) =
    query match {
      case Map(query, mapper) =>
        parallelize(query) match {
          case (constructors, assembler) =>
            (constructors, assembler.andThen(mapper))
        }

      case zip @ Zip(_, _, _) =>
        val (constructorsLeft, assemblerLeft)   = parallelize(zip.left)
        val (constructorsRight, assemblerRight) = parallelize(zip.right)
        (
          constructorsLeft ++ constructorsRight,
          (results: Chunk[Any]) => {
            val (leftResults, rightResults) = results.splitAt(constructorsLeft.length)
            val left                        = assemblerLeft(leftResults)
            val right                       = assemblerRight(rightResults)
            zip.zippable.zip(left, right)
          }
        )

      case Succeed(value)     => (Chunk.empty, _ => value())

      case batchGetItem @ BatchGetItem(_, _, _, _)            =>
        (
          Chunk(batchGetItem),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case batchWriteItem @ BatchWriteItem(_, _, _, _, _)     =>
        (
          Chunk(batchWriteItem),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case deleteTable @ DeleteTable(_)                       =>
        (
          Chunk(deleteTable),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case describeTable @ DescribeTable(_)                   =>
        (
          Chunk(describeTable),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case getItem @ GetItem(_, _, _, _, _)                   =>
        (
          Chunk(getItem),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case putItem @ PutItem(_, _, _, _, _, _)                =>
        (
          Chunk(putItem),
          (results: Chunk[Any]) => {
            if (results.isEmpty) ().asInstanceOf[A] else results.head.asInstanceOf[A]
          }
        )

      case updateItem @ UpdateItem(_, _, _, _, _, _, _)       =>
        (
          Chunk(updateItem),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case deleteItem @ DeleteItem(_, _, _, _, _, _)          =>
        (
          Chunk(deleteItem),
          (results: Chunk[Any]) => {
            if (results.isEmpty) ().asInstanceOf[A] else results.head.asInstanceOf[A]
          }
        )

      case scan @ ScanSome(_, _, _, _, _, _, _, _, _)         =>
        (
          Chunk(scan),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case scan @ ScanAll(_, _, _, _, _, _, _, _, _, _)       =>
        (
          Chunk(scan),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case query @ QuerySome(_, _, _, _, _, _, _, _, _, _, _) =>
        (
          Chunk(query),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case query @ QueryAll(_, _, _, _, _, _, _, _, _, _, _)  =>
        (
          Chunk(query),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

      case createTable @ CreateTable(_, _, _, _, _, _, _, _)  =>
        (
          Chunk(createTable),
          (results: Chunk[Any]) => {
            results.head.asInstanceOf[A]
          }
        )

    }

}
