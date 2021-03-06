package graph

import java.io.PrintWriter
import java.util.concurrent.Executors

import com.typesafe.scalalogging._
import graph.Graph._
import graph.Timer._

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

case class BFSTraversalFromSingleVertexResult(generatedBFSTraversal: BFSTraversal,
                                              timeForCompletionInMilliseconds: Long,
                                              threadID: Long)

case class BFSTraversalFromAllVerticesResult(allResults: List[BFSTraversalFromSingleVertexResult],
                                             timeForCompletionInMilliseconds: Long,
                                             numberOfThreads: Int)

case class Graph(adjMatrix: AdjMatrix) extends StrictLogging {

  def getVertices: Set[Vertex] = if (adjMatrix.isEmpty) Set.empty else List.range(0, adjMatrix.size).toSet

  def getNumVertices = adjMatrix.size

  def hasVertex(v: Vertex) = 0 <= v && v <= adjMatrix.size - 1

  def hasEdge(v1: Vertex, v2: Vertex): Either[String, Boolean] = {
    if (!hasVertex(v1))
      Left("Vertex " + v1 + " is not in the graph.")
    else if (!hasVertex(v2))
      Left("Vertex " + v2 + " is not in the graph.")
    else
      Right(adjMatrix(v1)(v2) == 1)
  }

  def getNeighbours(v: Vertex): Either[String, Set[Vertex]] = {
    if (hasVertex(v)) {
      Right(List.range(0, getNumVertices).filter(v1 => adjMatrix(v)(v1) == 1).toSet)
    }
    else {
      Left("No such vertex in the graph!")
    }
  }

  def writeToFile(file: String): Unit = new PrintWriter(file) {
    write(formattedForFile)
    close
  }

  private def formattedForFile: String = getNumVertices + "\n" + adjMatrix.map(_.mkString(" ")).reduceLeft(_ + "\n" + _)

  override def toString = formattedForFile

  def bfsTraversalStartingFromAllVertices(numberOfTasks: Int): BFSTraversalFromAllVerticesResult = {
    logger.debug("Starting BFS traversal from all vertices (" + getNumVertices + ") with number of tasks: " + numberOfTasks)

    val threadPool = Executors.newFixedThreadPool(numberOfTasks)
    implicit val ec = ExecutionContext.fromExecutor(threadPool)

    if (numberOfTasks < 1) {
      throw new IllegalArgumentException("Number of tasks for bfs traversal cannot be less than 1!")
    }

    val sortedListOfVertices = getVertices.toList.sorted

    val calculation = time {
      sortedListOfVertices.map(start_BFS_task_from_vertex).map(Await.result(_, Duration.Inf))
    }

    threadPool.shutdown

    logger.debug("Total number of threads used in current run: " + calculation.result.map(_.threadID).distinct.size)
    logger.debug("Total time elapsed (milliseconds) in current run: "
      + calculation.timeElapsedInMilliseconds + "\n----------------\n")

    BFSTraversalFromAllVerticesResult(
      allResults = calculation.result,
      timeForCompletionInMilliseconds = calculation.timeElapsedInMilliseconds,
      numberOfThreads = numberOfTasks)
  }

  private[graph] def bfsTraversalFrom(start: Vertex): BFSTraversal = {
    @tailrec
    def bfs(toVisit: Queue[Vertex], reached: Set[Vertex], path: BFSTraversal): BFSTraversal = {
      if (toVisit.isEmpty) path
      else {
        val current = toVisit.head
        val newNeighbours = getNeighbours(current).right.get.filter(!reached(_))

        bfs(
          toVisit.dequeue._2.enqueue(newNeighbours),
          reached ++ newNeighbours,
          current :: path
        )
      }
    }

    bfs(Queue(start), Set(start), List.empty).reverse
  }

  private def start_BFS_task_from_vertex(startingVertex: Vertex)
                                        (implicit ec: ExecutionContext) = Future {
    logger.debug("Start BFS from vertex " + startingVertex)
    val calculation = time {
      bfsTraversalFrom(startingVertex)
    }

    logger.debug("Finish BFS started from vertex " + startingVertex
      + ". Time elapsed in milliseconds: " + calculation.timeElapsedInMilliseconds)

    BFSTraversalFromSingleVertexResult(
      calculation.result,
      calculation.timeElapsedInMilliseconds,
      Thread.currentThread.getName.split("-").last.toLong)
  }
}

case object Graph {
  type Row = Array[Int]

  def Row(xs: Int*) = Array(xs: _*)

  type AdjMatrix = Array[Row]

  def AdjMatrix(xs: Row*) = Array(xs: _*)

  type Vertex = Int
  type BFSTraversal = List[Vertex]

  def apply(adjMatrix: AdjMatrix): Graph = {
    def checkAdjMatrixValidity = {
      adjMatrix.foreach(row => {
        if (row.size != adjMatrix.size)
          throw new IllegalArgumentException("Adjacency matrix has incorrect dimensions!")
        else row.foreach(vertex => {
          if (vertex != 0 && vertex != 1)
            throw new IllegalArgumentException(
              "Incorrect value in the matrix. Each value in the matrix should be either 1 or 0!")
        })
      })
    }

    checkAdjMatrixValidity
    new Graph(adjMatrix)
  }

  // sample file format for graph with 3 vertices:
  // 3
  // 0 1 0
  // 1 0 1
  // 0 0 1
  def fromFile(file: String) = {
    val fileSource = scala.io.Source.fromFile(file)
    val fileContent = fileSource.getLines.toArray

    try {
      if (fileContent.size != fileContent.head.toInt + 1)
        throw new IllegalArgumentException("Graph file '" + file + "' has invalid format!")
    }
    catch {
      case _: Throwable => throw new IllegalArgumentException("Graph file '" + file + "' has invalid format!")
    }

    fileSource.close

    val adjMatrix =
      fileContent.tail // size of the matrix is not needed, ignore it
        .map(_.split(" "))
        .map(row => row.map(_.toInt))

    new Graph(adjMatrix)
  }

  def withRandomEdges(numberOfVertices: Int) = {
    if (numberOfVertices < 0) {
      throw new IllegalArgumentException("Graph cannot have negative number of vertices!")
    }

    // the matrix has to be symmetrical
    val adjMatrix = Array.fill(numberOfVertices)(Array.fill(numberOfVertices)(0))

    for (i <- 0 until numberOfVertices; j <- 0 to i) {
      val randomEdge = Random.nextInt(2)

      adjMatrix(i)(j) = randomEdge
      adjMatrix(j)(i) = randomEdge
    }

    new Graph(adjMatrix)
  }

  def empty = new Graph(Array.empty)
}

