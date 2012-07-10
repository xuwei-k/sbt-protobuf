package sbtprotobuf

import sbt._
import Process._
import Keys._

import java.io.File


object ProtobufPlugin extends Plugin {
  type GeneratorTask = TaskKey[Seq[File]]

  object ProtobufKeys {
    val protobufConfig = config("protobuf")

    val includePaths = TaskKey[Seq[File]]("include-paths", "The paths that contain *.proto dependencies.")
    val protoc = SettingKey[String]("protoc", "The path+name of the protoc executable.")
    val externalIncludePath = SettingKey[File]("external-include-path", "The path to which protobuf:library-dependencies are extracted and which is used as protobuf:include-path for protoc")

    val generate = TaskKey[Seq[File]]("generate", "Compile the protobuf sources.")
    val unpackDependencies = TaskKey[UnpackedDependencies]("unpack-dependencies", "Unpack dependencies.")
    val generateJava = TaskKey[Seq[File]]("generate-java", "Compile the protobuf sources to java.")
  }

  import ProtobufKeys._


  class ProtobufSettings(generator: GeneratorTask, config: Configuration) {
    lazy val settings = buildSettings(generator, config)

    lazy val compileDependencies = sourceDirectories in generator in config <+= externalIncludePath in generator in config
  }

  abstract class SettingsFactory(generator: GeneratorTask) {
    def compile = new ProtobufSettings(generator, Compile)
    def test = new ProtobufSettings(generator, Test)
  }

  object PROTOC extends SettingsFactory(generateJava)



  def buildSettings(task: GeneratorTask, config: Configuration) = inConfig(config)(Seq[Setting[_]](
    sourceDirectory in task <<= sourceDirectory { _ / "protobuf" },
    sourceDirectories in task <<= (sourceDirectory in task)(Seq apply _),
    javaSource in task <<= sourceManaged { _ / "compiled_protobuf" },
    externalIncludePath in task <<= target { _ / "protobuf_external" },
    protoc in task := "protoc",
    version in task := "2.4.1",

    unpackDependencies in task <<= unpackDependenciesTask(task, config),

    includePaths in task <<= (sourceDirectories in task) map identity,
    includePaths in task <+= (unpackDependencies in task) map { _.dir },

    task <<= sourceGeneratorTask(task, config),

    sourceGenerators <+= task,
    managedSourceDirectories <+= javaSource in task,
    cleanFiles <+= javaSource in task
  )) ++ Seq[Setting[_]](
    libraryDependencies <+= (version in task in config)(v => "com.google.protobuf" % "protobuf-java" % v % config.name)
  )

  case class UnpackedDependencies(dir: File, files: Seq[File])

  private def executeProtoc(protocCommand: String, srcDirs: Seq[File], target: File, includePaths: Seq[File], log: Logger) =
    try {
      val schemas = srcDirs flatMap (d => (d ** "*.proto").get)
      val incPath = includePaths.map(_.absolutePath).mkString("-I", " -I", "")
      <x>{protocCommand} {incPath} --java_out={target.absolutePath} {schemas.map(_.absolutePath).mkString(" ")}</x> ! log
    } catch { case e: Exception =>
      throw new RuntimeException("error occured while compiling protobuf files: %s" format(e.getMessage), e)
    }


  private def compile(protocCommand: String, srcDirs: Seq[File], target: File, includePaths: Seq[File], log: Logger) = {
    val schemas = srcDirs flatMap (d => (d ** "*.proto").get)
    target.mkdirs()
    log.info("Compiling %d protobuf files to %s".format(schemas.size, target))
    schemas.foreach { schema => log.info("Compiling schema %s" format schema) }

    val exitCode = executeProtoc(protocCommand, srcDirs, target, includePaths, log)
    if (exitCode != 0)
      sys.error("protoc returned exit code: %d" format exitCode)

    (target ** "*.java").get.toSet
  }

  private def unpack(deps: Seq[File], extractTarget: File, log: Logger): Seq[File] = {
    IO.createDirectory(extractTarget)
    deps.flatMap { dep =>
      val seq = IO.unzip(dep, extractTarget, "*.proto").toSeq
      if (!seq.isEmpty) log.debug("Extracted " + seq.mkString(","))
      seq
    }
  }

  private def sourceGeneratorTask(task: GeneratorTask, config: Configuration) = (streams in task in config, sourceDirectories in task in config, javaSource in task in config, includePaths in task in config, cacheDirectory, protoc in task in config) map {
    (out, srcDirs, targetDir, includePaths, cache, protocCommand) =>
      val cachedCompile = FileFunction.cached(cache / "protobuf", inStyle = FilesInfo.lastModified, outStyle = FilesInfo.exists) { (in: Set[File]) =>
        compile(protocCommand, srcDirs, targetDir, includePaths, out.log)
      }
      cachedCompile(srcDirs flatMap (d => (d ** "*.proto").get) toSet).toSeq
  }

  private def unpackDependenciesTask(task: GeneratorTask, config: Configuration) = (streams in task in config, managedClasspath in task in config, externalIncludePath in task in config) map {
    (out, deps, extractTarget) =>
      val extractedFiles = unpack(deps.map(_.data), extractTarget, out.log)
      UnpackedDependencies(extractTarget, extractedFiles)
  }
}
