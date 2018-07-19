/*
 * Copyright 2017 TEAM PER LA TRASFORMAZIONE DIGITALE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package daf.stream

import java.util.concurrent.TimeUnit

import config.KafkaConfig
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.{ DataStreamWriter, Trigger }
import representation._

import scala.util.{ Failure, Try }

class StreamService(kafkaConfig: KafkaConfig) {

  private val sparkSession = SparkSession.builder()
    .master("local")
    .appName("IoT-Ingestion-Manager")
    .config("spark.driver.memory", "256m")
    .config("spark.executor.memory", "512m")
    .getOrCreate()

  def findStream(id: String) = sparkSession.streams.active.toSeq.find { _.name == id }

  private def prepareStream(streamData: StreamData) = streamData.source match {
    case KafkaSource(topic)       => prepareKafka(streamData.id, streamData.interval, topic)
    case SocketSource(host, port) => prepareSocket(streamData.id, streamData.interval, host, port)
  }

  private def startStream[A](streamData: StreamData, stream: DataStreamWriter[A]) = streamData.sink match {
    case ConsoleSink    => Try { stream.format("console").start() }
    case HdfsSink(path) => Try { stream.format("parquet").option("path", path).start() }
    case KuduSink(_)    => Failure { new IllegalArgumentException(s"Kudu sink is not supported for stream id [${streamData.id}]") }
  }

  private def prepareSocket(id: String, interval: Long, host: String, port: Int) = Try {
    sparkSession
      .readStream
      .format("socket")
      .option("host", host)
      .option("port", port)
      .load()
      .writeStream
      .queryName(id)
      .trigger { Trigger.ProcessingTime(interval, TimeUnit.SECONDS) }
  }

  private def prepareKafka(id: String, interval: Long, topic: String) = Try {
    sparkSession.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaConfig.servers mkString ",")
      .option("subscribe", topic)
      .load()
      .writeStream
      .queryName(id)
      .trigger { Trigger.ProcessingTime(interval, TimeUnit.SECONDS) }
  }

  def createStream(streamData: StreamData) = for {
    stream <- prepareStream(streamData)
    query  <- startStream(streamData, stream)
  } yield query

}
