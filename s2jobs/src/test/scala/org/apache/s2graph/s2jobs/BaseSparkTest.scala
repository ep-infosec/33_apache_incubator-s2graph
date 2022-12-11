/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.s2graph.s2jobs

import java.io.{File, PrintWriter}

import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.s2graph.core.Management.JsonModel.{Index, Prop}
import org.apache.s2graph.core.schema.{Label, Service, ServiceColumn}
import org.apache.s2graph.core.{Management, S2Graph}
import org.apache.s2graph.core.types.HBaseType
import org.apache.s2graph.s2jobs.loader.GraphFileOptions
import org.apache.s2graph.s2jobs.task.TaskConf
import org.apache.s2graph.spark.sql.streaming.S2SinkConfigs
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.util.Try

class BaseSparkTest extends FunSuite with Matchers with BeforeAndAfterAll with DataFrameSuiteBase {
  import org.apache.s2graph.core.S2GraphConfigs._

  protected val bulkloadOptions = new TaskConf("bulkloadOptions", "test", options = Map(
    DBConfigs.DB_DEFAULT_DRIVER -> "org.h2.Driver",
    DBConfigs.DB_DEFAULT_URL -> "jdbc:h2:file:./var/metastore_jobs;MODE=MYSQL",
    DBConfigs.DB_DEFAULT_USER -> "sa",
    DBConfigs.DB_DEFAULT_PASSWORD -> "sa",
    HBaseConfigs.HBASE_ZOOKEEPER_QUORUM -> "localhost",

    S2SinkConfigs.S2_SINK_WRITE_METHOD -> "bulk",

    S2SinkConfigs.S2_SINK_BULKLOAD_HBASE_TABLE_NAME -> "s2graph",
    S2SinkConfigs.S2_SINK_BULKLOAD_HBASE_NUM_REGIONS -> "3",
    S2SinkConfigs.S2_SINK_BULKLOAD_HBASE_TEMP_DIR -> "/tmp/bulkload_tmp",
    S2SinkConfigs.S2_SINK_BULKLOAD_HBASE_INCREMENTAL_LOAD -> "true",
    S2SinkConfigs.S2_SINK_BULKLOAD_HBASE_COMPRESSION -> "NONE",

    S2SinkConfigs.S2_SINK_BULKLOAD_AUTO_EDGE_CREATE -> "false",
    S2SinkConfigs.S2_SINK_BULKLOAD_BUILD_DEGREE -> "false",
    S2SinkConfigs.S2_SINK_BULKLOAD_LABEL_MAPPING -> ""
  ))
  protected val options: GraphFileOptions = GraphFileOptions(
    input = "/tmp/test.txt",
    tempDir = "/tmp/bulkload_tmp",
    output = "/tmp/s2graph_bulkload",
    zkQuorum = "localhost",
    dbUrl = "jdbc:h2:file:./var/metastore_jobs;MODE=MYSQL",
    dbUser = "sa",
    dbPassword = "sa",
    dbDriver = "org.h2.Driver",
    tableName = "s2graph",
    maxHFilePerRegionServer = 1,
    numRegions = 3,
    compressionAlgorithm = "NONE",
    buildDegree = false,
    autoEdgeCreate = false)

  protected val s2Config = Management.toConfig(options.toConfigParams)

  protected val tableName = options.tableName
  protected val schemaVersion = HBaseType.DEFAULT_VERSION
  protected val compressionAlgorithm: String = options.compressionAlgorithm
  protected var s2: S2Graph = _

  private val testLines = Seq(
    "20171201\tinsert\tvertex\t800188448586078\tdevice_profile\timei\t{\"first_time\":\"20171025\",\"last_time\":\"20171112\",\"total_active_days\":14,\"query_amount\":1526.0,\"active_months\":2,\"fua\":\"M5+Note\",\"location_often_province\":\"广东省\",\"location_often_city\":\"深圳市\",\"location_often_days\":6,\"location_last_province\":\"广东省\",\"location_last_city\":\"深圳市\",\"fimei_legality\":3}"
  )

  override def beforeAll(): Unit = {

    // initialize spark context.
    super.beforeAll()
    s2 = S2GraphHelper.getS2Graph(s2Config, true)

    S2Graph.loadFetchersAndMutators(s2)
    
    deleteRecursively(new File(options.output))
    deleteRecursively(new File(options.tempDir))

    def createDummyHbaseTable(hTableName: String): Unit = {
      Try {
        val hTables = Service.findAll().map(_.hTableName).toSet
        if (!hTables(hTableName)) {
          s2.management.createService(serviceName = s"_dummy_${hTableName}", cluster = "localhost",
            hTableName = hTableName, preSplitSize = -1, hTableTTL = -1, compressionAlgorithm = "gz")
        }
      }
    }

    Seq(
      "s2graph",
      "_test_cases",
      "test-htable",
      "label_with_ttl",
      "label_without_ttl",
      "label_with_ttl_copied",
      "label_without_ttl_copied",
      "s2graph_with_ttl-dev",
      "s2graph_without_ttl-dev",
      "s2graph_label_test_copied"
    ).foreach(createDummyHbaseTable)

    initTestDataFile
  }

  override def afterAll(): Unit = {
    super.afterAll()

    if (s2 != null) {
      s2.shutdown()
    }
  }

  def initTestDataFile: Unit = {
    deleteRecursively(new File(options.input))
    writeToFile(options.input)(testLines)
  }

  def initTestEdgeSchema(s2: S2Graph, tableName: String,
                         schemaVersion: String = HBaseType.DEFAULT_VERSION,
                         compressionAlgorithm: String = "none"): Label = {
    import scala.collection.JavaConverters._
    /* initialize model for test */
    val management = s2.management

    val service = management.createService(serviceName = "s2graph", cluster = "localhost",
      hTableName = "s2graph", preSplitSize = -1, hTableTTL = -1, compressionAlgorithm = "gz")

    val serviceColumn = management.createServiceColumn(service.serviceName, "user", "string", Nil)

    Try {
      management.createLabel("friends",
        serviceColumn.service.serviceName, serviceColumn.columnName, serviceColumn.columnType,
        serviceColumn.service.serviceName, serviceColumn.columnName, serviceColumn.columnType,
        serviceName = service.serviceName, indices = Nil,
        props = Seq(Prop("since", "0", "long"), Prop("score", "0", "integer")),
        isDirected = true, consistencyLevel = "strong", hTableName = Option(tableName),
        hTableTTL = None, schemaVersion = schemaVersion, compressionAlgorithm = compressionAlgorithm, options = None)
    }

    Label.findByName("friends").getOrElse(throw new IllegalArgumentException("friends label is not initialized."))
  }

  def initTestVertexSchema(s2: S2Graph): ServiceColumn = {
    /* initialize model for test */
    val management = s2.management

    val service = management.createService(serviceName = "device_profile", cluster = "localhost",
      hTableName = "s2graph", preSplitSize = -1, hTableTTL = -1, compressionAlgorithm = "gz")

    management.createServiceColumn(service.serviceName, "imei", "string",
      Seq(
        Prop(name = "first_time", defaultValue = "''", dataType = "string"),
        Prop(name = "last_time", defaultValue = "''", dataType = "string"),
        Prop(name = "total_active_days", defaultValue = "-1", dataType = "integer"),
        Prop(name = "query_amount", defaultValue = "-1", dataType = "integer"),
        Prop(name = "active_months", defaultValue = "-1", dataType = "integer"),
        Prop(name = "fua", defaultValue = "''", dataType = "string"),
        Prop(name = "location_often_province", defaultValue = "''", dataType = "string"),
        Prop(name = "location_often_city", defaultValue = "''", dataType = "string"),
        Prop(name = "location_often_days", defaultValue = "-1", dataType = "integer"),
        Prop(name = "location_last_province", defaultValue = "''", dataType = "string"),
        Prop(name = "location_last_city", defaultValue = "''", dataType = "string"),
        Prop(name = "fimei_legality", defaultValue = "-1", dataType = "integer")
      ))
  }

  def writeToFile(fileName: String)(lines: Seq[String]): Unit = {
    val writer = new PrintWriter(fileName)
    lines.foreach(line => writer.write(line + "\n"))
    writer.close
  }

  def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) file.listFiles.foreach(deleteRecursively)
    if (file.exists && !file.delete) throw new Exception(s"Unable to delete ${file.getAbsolutePath}")
  }
}
