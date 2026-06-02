package io.joern.x2cpg.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

object FrontendProfiling {

  private final case class RunContext(
    frontend: String,
    inputPath: String,
    timesNanos: ConcurrentHashMap[String, LongAdder],
    metrics: ConcurrentHashMap[String, String]
  )

  private val contextTl: ThreadLocal[RunContext] = new ThreadLocal[RunContext]()

  def enabled: Boolean = {
    val v = sys.env.getOrElse("JOERN_PROFILE", "")
    v == "1" || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes")
  }

  def run[A](frontend: String, inputPath: String)(f: => A): A = {
    if (!enabled) {
      f
    } else {
      start(frontend, inputPath)
      try {
        f
      } finally {
        end()
      }
    }
  }

  def time[A](name: String)(f: => A): A = {
    val ctx = contextTl.get()
    if (ctx == null) {
      f
    } else {
      println(s"[PROFILE] begin stage=$name")
      val t0 = System.nanoTime()
      try {
        val out = f
        out
      } finally {
        val dt = System.nanoTime() - t0
        ctx.timesNanos.computeIfAbsent(name, _ => new LongAdder()).add(dt)
        println(s"[PROFILE] end stage=$name elapsed=${TimeUtils.pretty(dt)}")
      }
    }
  }

  def metric(name: String, value: String): Unit = {
    val ctx = contextTl.get()
    if (ctx != null) {
      ctx.metrics.put(name, value)
    }
  }

  def metric(name: String, value: Long): Unit = metric(name, value.toString)

  private def start(frontend: String, inputPath: String): Unit = {
    val ctx = RunContext(frontend, inputPath, new ConcurrentHashMap[String, LongAdder](), new ConcurrentHashMap[String, String]())
    contextTl.set(ctx)
    println(s"[PROFILE] start frontend=$frontend input=$inputPath")
  }

  private def end(): Unit = {
    val ctx = contextTl.get()
    if (ctx != null) {
      val totals = ctx.timesNanos.asScala.view.mapValues(_.sum()).toMap
      val totalNanos = totals.values.sum
      val top = totals.toList.sortBy(-_._2)
      val metricText =
        if (ctx.metrics.isEmpty) ""
        else ctx.metrics.asScala.toList.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString(" ")

      println(s"[PROFILE] end frontend=${ctx.frontend} total=${TimeUtils.pretty(totalNanos)} $metricText".trim)
      top.foreach { case (name, nanos) =>
        val pct = if (totalNanos == 0) 0.0 else nanos.toDouble * 100.0 / totalNanos.toDouble
        println(f"[PROFILE] stage=$name elapsed=${TimeUtils.pretty(nanos)} share=$pct%.2f%%")
      }
    }
    contextTl.remove()
  }
}
