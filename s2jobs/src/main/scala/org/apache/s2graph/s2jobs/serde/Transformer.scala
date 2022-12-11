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

package org.apache.s2graph.s2jobs.serde

import com.typesafe.config.Config
import org.apache.s2graph.core.GraphElement

import scala.reflect.ClassTag

/**
  * Define serialize/deserialize.
  * Source -> GraphElement
  * GraphElement -> Target
  *
  * @tparam M : Container type. ex) RDD, Seq, List, ...
  */
trait Transformer[M[_]] extends Serializable {
  val config: Config

  def buildDegrees[T: ClassTag](elements: M[GraphElement])(implicit writer: GraphElementWritable[T]): M[T]

  def transform[S: ClassTag, T: ClassTag](input: M[S])
                           (implicit reader: GraphElementReadable[S], writer: GraphElementWritable[T]): M[T]
}
