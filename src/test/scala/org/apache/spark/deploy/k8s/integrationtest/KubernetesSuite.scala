/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.deploy.k8s.integrationtest

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.UUID
import java.util.regex.Pattern

import scala.collection.JavaConverters._
import com.google.common.io.PatternFilenameFilter
import io.fabric8.kubernetes.api.model.{Container, Pod}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FunSuite}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration}
import org.scalatest.time.{Minutes, Seconds, Span}

import org.apache.spark.deploy.k8s.integrationtest.backend.IntegrationTestBackendFactory
import org.apache.spark.deploy.k8s.integrationtest.backend.minikube.MinikubeTestBackend
import org.apache.spark.deploy.k8s.integrationtest.config._

private[spark] class KubernetesSuite extends FunSuite with BeforeAndAfterAll with BeforeAndAfter {

  import KubernetesSuite._
  private val testBackend = IntegrationTestBackendFactory.getTestBackend()
  private val APP_LOCATOR_LABEL = UUID.randomUUID().toString.replaceAll("-", "")
  private var sparkHomeDir: Path = _
  private var kubernetesTestComponents: KubernetesTestComponents = _
  private var sparkAppConf: SparkAppConf = _
  private var image: String = _
  private var containerLocalSparkDistroExamplesJar: String = _

  override def beforeAll(): Unit = {
    val sparkDirProp = System.getProperty("spark.kubernetes.test.unpackSparkDir")
    require(sparkDirProp != null, "Spark home directory must be provided in system properties.")
    sparkHomeDir = Paths.get(sparkDirProp)
    require(sparkHomeDir.toFile.isDirectory,
      s"No directory found for spark home specified at $sparkHomeDir.")
    val imageTag = getTestImageTag
    val imageRepo = getTestImageRepo
    image = s"$imageRepo/spark:$imageTag"

    val sparkDistroExamplesJarFile: File = sparkHomeDir.resolve(Paths.get("examples", "jars"))
      .toFile
      .listFiles(new PatternFilenameFilter(Pattern.compile("^spark-examples_.*\\.jar$")))(0)
    containerLocalSparkDistroExamplesJar = s"local:///opt/spark/examples/jars/" +
      s"${sparkDistroExamplesJarFile.getName}"
    testBackend.initialize()
    kubernetesTestComponents = new KubernetesTestComponents(testBackend.getKubernetesClient)
  }

  override def afterAll(): Unit = {
    testBackend.cleanUp()
  }

  before {
    sparkAppConf = kubernetesTestComponents.newSparkAppConf()
      .set("spark.kubernetes.container.image", image)
      .set("spark.kubernetes.driver.label.spark-app-locator", APP_LOCATOR_LABEL)
      .set("spark.kubernetes.executor.label.spark-app-locator", APP_LOCATOR_LABEL)
    kubernetesTestComponents.createNamespace()
  }

  after {
    kubernetesTestComponents.deleteNamespace()
  }

  test("Run SparkPi with no resources") {
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with a very long application name.") {
    sparkAppConf.set("spark.app.name", "long" * 40)
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with a master URL without a scheme.") {
    val url = kubernetesTestComponents.kubernetesClient.getMasterUrl
    val k8sMasterUrl = if (url.getPort < 0) {
      s"k8s://${url.getHost}"
    } else {
      s"k8s://${url.getHost}:${url.getPort}"
    }
    sparkAppConf.set("spark.master", k8sMasterUrl)
    runSparkPiAndVerifyCompletion()
  }

  test("Run SparkPi with an argument.") {
    runSparkPiAndVerifyCompletion(appArgs = Array("5"))
  }

  test("Run SparkPi with custom driver pod name, labels, annotations, and environment variables.") {
    sparkAppConf
      .set("spark.kubernetes.driver.pod.name", "spark-integration-spark-pi")
      .set("spark.kubernetes.driver.label.label1", "label1-value")
      .set("spark.kubernetes.driver.label.label2", "label2-value")
      .set("spark.kubernetes.driver.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.driver.annotation.annotation2", "annotation2-value")
      .set("spark.kubernetes.driverEnv.ENV1", "VALUE1")
      .set("spark.kubernetes.driverEnv.ENV2", "VALUE2")
      .set("spark.kubernetes.executor.label.label1", "label1-value")
      .set("spark.kubernetes.executor.label.label2", "label2-value")
      .set("spark.kubernetes.executor.annotation.annotation1", "annotation1-value")
      .set("spark.kubernetes.executor.annotation.annotation2", "annotation2-value")
      .set("spark.executorEnv.ENV1", "VALUE1")
      .set("spark.executorEnv.ENV2", "VALUE2")

    runSparkPiAndVerifyCompletion(
      driverPodChecker = (driverPod: Pod) => {
        doBasicDriverPodCheck(driverPod)
        assert(driverPod.getMetadata.getName === "spark-integration-spark-pi")
        checkCustomSettings(driverPod)
      },
      executorPodChecker = (executorPod: Pod) => {
        doBasicExecutorPodCheck(executorPod)
        checkCustomSettings(executorPod)
      })
  }

  test("Run SparkPi with a test secret mounted into the driver and executor pods") {
    createTestSecret()
    sparkAppConf
      .set(s"spark.kubernetes.driver.secrets.$TEST_SECRET_NAME", TEST_SECRET_MOUNT_PATH)
      .set(s"spark.kubernetes.executor.secrets.$TEST_SECRET_NAME", TEST_SECRET_MOUNT_PATH)
    runSparkPiAndVerifyCompletion(
      driverPodChecker = (driverPod: Pod) => {
        doBasicDriverPodCheck(driverPod)
        checkTestSecret(driverPod)
      },
      executorPodChecker = (executorPod: Pod) => {
        doBasicExecutorPodCheck(executorPod)
        checkTestSecret(executorPod)
      })
  }

  test("Run PageRank using remote data file") {
    sparkAppConf
      .set("spark.kubernetes.mountDependencies.filesDownloadDir",
        CONTAINER_LOCAL_FILE_DOWNLOAD_PATH)
      .set("spark.files", REMOTE_PAGE_RANK_DATA_FILE)
    runSparkPageRankAndVerifyCompletion(
      appArgs = Array(CONTAINER_LOCAL_DOWNLOADED_PAGE_RANK_DATA_FILE))
  }

  test("Run PageRank using remote data file with test secret mounted into the driver and " +
    "executors") {
    sparkAppConf
      .set("spark.kubernetes.mountDependencies.filesDownloadDir",
        CONTAINER_LOCAL_FILE_DOWNLOAD_PATH)
      .set("spark.files", REMOTE_PAGE_RANK_DATA_FILE)
      .set(s"spark.kubernetes.driver.secrets.$TEST_SECRET_NAME", TEST_SECRET_MOUNT_PATH)
      .set(s"spark.kubernetes.executor.secrets.$TEST_SECRET_NAME", TEST_SECRET_MOUNT_PATH)

    createTestSecret()

    runSparkPageRankAndVerifyCompletion(
      appArgs = Array(CONTAINER_LOCAL_DOWNLOADED_PAGE_RANK_DATA_FILE),
      driverPodChecker = (driverPod: Pod) => {
        doBasicDriverPodCheck(driverPod)
        checkTestSecret(driverPod, withInitContainer = true)
      },
      executorPodChecker = (executorPod: Pod) => {
        doBasicExecutorPodCheck(executorPod)
        checkTestSecret(executorPod, withInitContainer = true)
      })
  }

  private def runSparkPiAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      executorPodChecker: Pod => Unit = doBasicExecutorPodCheck,
      appArgs: Array[String] = Array.empty[String]): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_PI_MAIN_CLASS,
      Seq("Pi is roughly 3"),
      appArgs,
      driverPodChecker,
      executorPodChecker)
  }
  private def runSparkPageRankAndVerifyCompletion(
      appResource: String = containerLocalSparkDistroExamplesJar,
      driverPodChecker: Pod => Unit = doBasicDriverPodCheck,
      executorPodChecker: Pod => Unit = doBasicExecutorPodCheck,
      appArgs: Array[String]): Unit = {
    runSparkApplicationAndVerifyCompletion(
      appResource,
      SPARK_PAGE_RANK_MAIN_CLASS,
      Seq("1 has rank", "2 has rank", "3 has rank", "4 has rank"),
      appArgs,
      driverPodChecker,
      executorPodChecker)
  }

  private def runSparkApplicationAndVerifyCompletion(
      appResource: String,
      mainClass: String,
      expectedLogOnCompletion: Seq[String],
      appArgs: Array[String],
      driverPodChecker: Pod => Unit,
      executorPodChecker: Pod => Unit): Unit = {
    val appArguments = SparkAppArguments(
      mainAppResource = appResource,
      mainClass = mainClass,
      appArgs = appArgs)
    SparkAppLauncher.launch(appArguments, sparkAppConf, TIMEOUT.value.toSeconds.toInt, sparkHomeDir)

    val driverPod = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", APP_LOCATOR_LABEL)
      .withLabel("spark-role", "driver")
      .list()
      .getItems
      .get(0)
    driverPodChecker(driverPod)

    val executorPods = kubernetesTestComponents.kubernetesClient
      .pods()
      .withLabel("spark-app-locator", APP_LOCATOR_LABEL)
      .withLabel("spark-role", "executor")
      .list()
      .getItems
    executorPods.asScala.foreach { pod =>
      executorPodChecker(pod)
    }

    Eventually.eventually(TIMEOUT, INTERVAL) {
      expectedLogOnCompletion.foreach { e =>
        assert(kubernetesTestComponents.kubernetesClient
          .pods()
          .withName(driverPod.getMetadata.getName)
          .getLog
          .contains(e), "The application did not complete.")
      }
    }
  }

  private def doBasicDriverPodCheck(driverPod: Pod): Unit = {
    assert(driverPod.getSpec.getContainers.get(0).getImage === image)
    assert(driverPod.getSpec.getContainers.get(0).getName === "spark-kubernetes-driver")
  }

  private def doBasicExecutorPodCheck(executorPod: Pod): Unit = {
    assert(executorPod.getSpec.getContainers.get(0).getImage === image)
    assert(executorPod.getSpec.getContainers.get(0).getName === "executor")
  }

  private def checkCustomSettings(pod: Pod): Unit = {
    assert(pod.getMetadata.getLabels.get("label1") === "label1-value")
    assert(pod.getMetadata.getLabels.get("label2") === "label2-value")
    assert(pod.getMetadata.getAnnotations.get("annotation1") === "annotation1-value")
    assert(pod.getMetadata.getAnnotations.get("annotation2") === "annotation2-value")

    val container = pod.getSpec.getContainers.get(0)
    val envVars = container
      .getEnv
      .asScala
      .map { env =>
        (env.getName, env.getValue)
      }
      .toMap
    assert(envVars("ENV1") === "VALUE1")
    assert(envVars("ENV2") === "VALUE2")
  }

  private def createTestSecret(): Unit = {
    testBackend.getKubernetesClient.secrets
      .createNew()
      .editOrNewMetadata()
        .withName(TEST_SECRET_NAME)
        .withNamespace(kubernetesTestComponents.namespace)
        .endMetadata()
      .addToStringData(TEST_SECRET_KEY, TEST_SECRET_VALUE)
      .done()
  }

  private def checkTestSecret(pod: Pod, withInitContainer: Boolean = false): Unit = {
    val testSecretVolume = pod.getSpec.getVolumes.asScala.filter { volume =>
      volume.getName == s"$TEST_SECRET_NAME-volume"
    }
    assert(testSecretVolume.size === 1)
    assert(testSecretVolume.head.getSecret.getSecretName === TEST_SECRET_NAME)

    checkTestSecretInContainer(pod.getSpec.getContainers.get(0))

    if (withInitContainer) {
      checkTestSecretInContainer(pod.getSpec.getInitContainers.get(0))
    }
  }

  private def checkTestSecretInContainer(container: Container): Unit = {
    val testSecret = container.getVolumeMounts.asScala.filter { mount =>
      mount.getName == s"$TEST_SECRET_NAME-volume"
    }
    assert(testSecret.size === 1)
    assert(testSecret.head.getMountPath === TEST_SECRET_MOUNT_PATH)
  }
}

private[spark] object KubernetesSuite {

  val TIMEOUT = PatienceConfiguration.Timeout(Span(2, Minutes))
  val INTERVAL = PatienceConfiguration.Interval(Span(2, Seconds))
  val SPARK_PI_MAIN_CLASS: String = "org.apache.spark.examples.SparkPi"
  val SPARK_PAGE_RANK_MAIN_CLASS: String = "org.apache.spark.examples.SparkPageRank"

  val TEST_SECRET_NAME = "test-secret"
  val TEST_SECRET_KEY = "test-key"
  val TEST_SECRET_VALUE = "test-data"
  val TEST_SECRET_MOUNT_PATH = "/etc/secrets"

  val CONTAINER_LOCAL_FILE_DOWNLOAD_PATH = "/var/spark-data/spark-files"

  val REMOTE_PAGE_RANK_DATA_FILE =
    "https://storage.googleapis.com/spark-k8s-integration-tests/files/pagerank_data.txt"
  val CONTAINER_LOCAL_DOWNLOADED_PAGE_RANK_DATA_FILE =
    s"$CONTAINER_LOCAL_FILE_DOWNLOAD_PATH/pagerank_data.txt"

  case object ShuffleNotReadyException extends Exception
}
