package io.joern.javasrc2cpg.typesolvers

import javassist.ClassPath
import javassist.bytecode.ClassFile
import org.slf4j.LoggerFactory

import java.io.{DataInputStream, InputStream}
import java.net.{URI, URL}
import java.nio.file.Paths
import java.util.jar.{JarEntry, JarFile}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

object BytecodeIndexedClassPath {

  /** Read the declared class name from an open `.class` file stream using javassist. */
  def readClassNameFrom(inputStream: InputStream): String =
    new ClassFile(new DataInputStream(inputStream)).getName

  def classNameFromEntryPath(entryPath: String): Option[String] = {
    val normalized = entryPath.stripPrefix("/")
    val strippedKnownPrefix = {
      val knownPrefixes = List("BOOT-INF/classes/", "WEB-INF/classes/", "classes/")
      knownPrefixes.collectFirst { case prefix if normalized.startsWith(prefix) => normalized.stripPrefix(prefix) }.getOrElse(normalized)
    }
    val strippedMultiReleaseVersion =
      if (strippedKnownPrefix.startsWith("META-INF/versions/")) {
        val rest = strippedKnownPrefix.stripPrefix("META-INF/versions/")
        rest.dropWhile(_.isDigit).stripPrefix("/")
      } else strippedKnownPrefix
    if (!strippedMultiReleaseVersion.endsWith(".class")) None
    else Some(strippedMultiReleaseVersion.stripSuffix(".class").replace('/', '.'))
  }

  def matchesAnyPrefix(className: String, prefixes: Seq[String]): Boolean =
    prefixes.exists { prefix =>
      if (prefix.isEmpty) true
      else if (prefix.endsWith(".")) className.startsWith(prefix)
      else className == prefix || className.startsWith(prefix + ".")
    }
}

/** A ClassPath implementation that resolves classes by their actual package declaration in bytecode rather than by
  * their path within the archive. This handles non-standard archive structures (e.g., fat JARs, repackaged JARs, JMODs)
  * where the entry path may not match the class's declared package.
  */
class BytecodeIndexedClassPath(archivePath: String, allowlist: Seq[String] = Seq.empty) extends ClassPath with AutoCloseable {

  private val logger     = LoggerFactory.getLogger(this.getClass)
  private val jarFile    = new JarFile(archivePath)
  private val jarFileURL = Paths.get(archivePath).toUri.toURL.toString
  private val urlScheme  = if (archivePath.endsWith(".jmod")) "jmod" else "jar"
  private val allowlistPrefixes = allowlist.distinct

  private val classNameToEntry: Map[String, JarEntry] = buildIndex()

  val knownClassNames: Set[String] = classNameToEntry.keySet

  private def buildIndex(): Map[String, JarEntry] = {
    val entries = jarFile.entries().asScala.filter(entry => !entry.isDirectory && entry.getName.endsWith(".class")).toList

    def buildByBytecodeRead: Map[String, JarEntry] =
      entries.flatMap { entry =>
        readClassName(entry) match {
          case Some(className) => Some(className -> entry)
          case None =>
            logger.debug(s"Could not read class name from entry ${entry.getName} in $archivePath")
            None
        }
      }.toMap

    if (allowlistPrefixes.isEmpty) {
      buildByBytecodeRead
    } else {
      val sampleSize  = math.min(50, entries.size)
      val isNonStandard = entries.iterator.take(sampleSize).exists { entry =>
        BytecodeIndexedClassPath.classNameFromEntryPath(entry.getName) match {
          case None => false
          case Some(byPath) =>
            readClassName(entry) match {
              case None           => false
              case Some(byBytecode) => byPath != byBytecode
            }
        }
      }

      if (isNonStandard) {
        buildByBytecodeRead
      } else {
        entries.iterator
          .flatMap { entry =>
            BytecodeIndexedClassPath.classNameFromEntryPath(entry.getName) match {
              case Some(byPath) if BytecodeIndexedClassPath.matchesAnyPrefix(byPath, allowlistPrefixes) =>
                Some(byPath -> entry)
              case Some(_) =>
                None
              case None =>
                readClassName(entry).filter(BytecodeIndexedClassPath.matchesAnyPrefix(_, allowlistPrefixes)).map(_ -> entry)
            }
          }
          .toMap
      }
    }
  }

  private def readClassName(entry: JarEntry): Option[String] =
    Try(Using.resource(jarFile.getInputStream(entry))(BytecodeIndexedClassPath.readClassNameFrom)).toOption

  override def find(classname: String): URL = {
    classNameToEntry
      .get(classname)
      .flatMap { entry =>
        Try(new URI(s"$urlScheme:$jarFileURL!/${entry.getName}").toURL).toOption
      }
      .orNull
  }

  override def openClassfile(classname: String): InputStream = {
    classNameToEntry.get(classname).map(jarFile.getInputStream).orNull
  }

  override def close(): Unit = jarFile.close()
}
