package org.specs2
package reporter

import specification._
import specification.process._
import core._
import scalaz.concurrent.Task
import control._
import scalaz.{Writer => _, _}, Scalaz._
import scalaz.stream._
import data._
import Fold._
import Statistics._

/**
 * A reporter is responsible for
 *  - selecting printers based on the command-line arguments
 *  - executing the specification
 *  - passing it to each printer for printing
 *
 * It is also responsible for saving the specification state at the end of the run
 */
trait Reporter {

  /**
   * report 1 spec structure with the given printers
   * first find and sort the linked specifications and report them
   */
  def report(env: Env, printers: List[Printer]): SpecStructure => Action[Unit] = { spec =>
    val env1 = env.setArguments(env.arguments.overrideWith(spec.arguments))
    val executing = readStats(spec, env1) |> env1.selector.select(env1) |> env1.executor.execute(env1)
    val folds = printers.map(_.fold(env1, spec)) :+ statsStoreFold(env1, spec)
    Actions.fromTask(runFolds(executing.contents, folds))
  }

  /**
   * Use a Fold to store the stats of each example + the stats of the specification
   */
  def statsStoreFold(env: Env, spec: SpecStructure) = new Fold[Fragment] {
    type S = Stats

    private val neverStore = env.arguments.store.never
    private val resetStore = env.arguments.store.reset

    def prepare: Task[Unit] =
      if (resetStore) env.statisticsRepository.resetStatistics.toTask
      else            Task.now(())

    lazy val sink: Sink[Task, (Fragment, Stats)] =
      io.channel {  case (fragment, stats) =>
        if (neverStore) Task.delay(())
        else            env.statisticsRepository.storeResult(spec.specClassName, fragment.description, fragment.executionResult).toTask
      }

    def fold = Statistics.fold
    def init = Stats.empty

    def last(stats: Stats) =
      if (neverStore) Task.now(())
      else            env.statisticsRepository.storeStatistics(spec.specClassName, stats).toTask
  }

}

object Reporter extends Reporter
