package dev.vhonta.news

import io.temporal.common.converter._
import scalapb.GeneratedFileObject
import zio.temporal.protobuf.ScalapbPayloadConverter

// TODO: backport to temporal
object ProtobufDataConverterWorkaround {

  /** Creates data converted supporting given protobuf generated types
    *
    * @param files
    *   generated protobuf files
    * @return
    *   a [[DataConverter]] supporting given protobuf types
    */
  def make(files: Seq[GeneratedFileObject]): DataConverter =
    new DefaultDataConverter(
      // order matters!
      Seq(
        new NullPayloadConverter(),
        new ByteArrayPayloadConverter(),
        new ProtobufJsonPayloadConverter(),
        new ScalapbPayloadConverter(files),
        new JacksonJsonPayloadConverter() // falling back to jackson for primitive types
      ): _*
    )

  /** Creates data converted supporting protobuf generated types. Loads all available protobuf descriptors generated by
    * protobuf.
    *
    * @param additionalFiles
    *   additional protobuf files to add
    * @return
    *   a [[DataConverter]] supporting given protobuf types
    */
  def makeAutoLoad(additionalFiles: Seq[GeneratedFileObject] = Nil): DataConverter = {
    val autoLoaded = ProtoFileObjectAutoLoaderWorkaround.loadFromClassPath(getClass.getClassLoader)
    make(autoLoaded ++ additionalFiles)
  }
}

// file 2
import org.reflections.Reflections
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import org.slf4j.LoggerFactory
import scala.jdk.CollectionConverters._

object ProtoFileObjectAutoLoaderWorkaround {
  private val logger           = LoggerFactory.getLogger(getClass)
  private val scalaModuleField = "MODULE$"

  def loadFromClassPath(
    classLoader:     ClassLoader,
    excludePrefixes: Set[String] = standardExcludePrefixes
  ): List[GeneratedFileObject] = {
    logger.trace(s"Provided exclude prefixes: ${excludePrefixes.mkString("[", ",", "]")}")

    val reflections = new Reflections(
      new ConfigurationBuilder()
        .filterInputsBy((s: String) => excludeRule(excludePrefixes)(s))
        .setUrls(
          (ClasspathHelper.forClassLoader(classLoader).asScala ++
            ClasspathHelper.forJavaClassPath().asScala).asJavaCollection
        )
    )

    val loadedSubTypes = reflections.getSubTypesOf(classOf[GeneratedFileObject]).asScala.toList
    logger.trace(s"Found subtypes of GeneratedFileObject: ${loadedSubTypes.mkString("[", ",", "]")}")
    val results = loadedSubTypes.map(getGeneratedObjectInstance)
    logger.info(
      s"Loaded ${results.size} GeneratedFileObject(s): ${results.map(showGeneratedFileObject).mkString("[", ",", "]")}"
    )
    results
  }

  def standardExcludePackages: Set[String] =
    Set(
      "META-INF",
      "akka",
      "android",
      "cats",
      "com.cronutils",
      "com.fasterxml",
      "com.google.api",
      "com.google.cloud",
      "com.google.common",
      "com.google.errorprone",
      "com.google.geo",
      "com.google.gson",
      "com.google.logging",
      "com.google.longrunning",
      "com.google.protobuf",
      "com.google.rpc",
      "com.google.thirdparty",
      "com.google.type",
      "com.sun",
      "com.sun",
      "com.thoughtworks",
      "com.typesafe.config",
      "com.uber.m3",
      "distage",
      "gogoproto",
      "io.grpc",
      "io.micrometer",
      "io.perfmark",
      "io.temporal",
      "io.temporal",
      "izumi",
      "java",
      "javax",
      "jdk",
      "logstage",
      "magnolia",
      "mercator",
      "net.sf.cglib",
      "org.HdrHistogram",
      "org.LatencyUtils",
      "org.apache.ivy",
      "org.checkerframework",
      "org.codehaus.mojo",
      "org.reflections",
      "org.scalatools",
      "org.slf4j",
      "pureconfig",
      "sbt",
      "scala",
      "sun",
      "xsbt",
      "xsbti",
      "zio",
      "zio.temporal"
    )

  def standardExcludePrefixes: Set[String] = {
    val excludePackages = standardExcludePackages
    val filesInPackages = excludePackages.map(packagePrefixToDirPrefix)
    Set("module-info.class") ++ excludePackages ++ filesInPackages
  }

  def excludeRule(excludes: Set[String])(s: String): Boolean =
    s.endsWith(".class") && !excludes.exists(s.startsWith)

  def packagePrefixToDirPrefix(pkg: String): String =
    pkg.replace(".", "/") + "/"

  private def getGeneratedObjectInstance(cls: Class[_ <: GeneratedFileObject]): GeneratedFileObject =
    cls
      .getDeclaredField(scalaModuleField)
      .get(null)
      .asInstanceOf[GeneratedFileObject]

  private def showGeneratedFileObject(f: GeneratedFileObject): String =
    s"GeneratedFileObject(class=${f.getClass.getName}, file=${f.scalaDescriptor.fullName})"
}
