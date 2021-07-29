/*
 * Tencent is pleased to support the open source community by making Angel available.
 *
 * Copyright (C) 2017-2018 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/Apache-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package com.tencent.angel.pytorch.eval

import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

class F1Score extends Evaluation {
  override def calculate(pairs: RDD[(Double, Double)]): Double = {
    pairs.persist(StorageLevel.MEMORY_ONLY)
    pairs.count()
    val partNum = pairs.getNumPartitions
    val tags = pairs.groupByKey(partNum)
      .persist(StorageLevel.MEMORY_ONLY)
    val classNum = tags.count().toInt
    val revTags = pairs.groupBy(f => f._2).repartition(partNum)
      .persist(StorageLevel.MEMORY_ONLY)
    revTags.count()
    pairs.unpersist(blocking = false)
    val f1 = if (classNum == 2) {
      val (tp, fn) = tags.map { info =>
        if (info._1 == 1) {
          info._2.map(o => if (o == 1) (1, 0) else (0, 1))
        }.reduce((a, b) => (a._1 + b._1, a._2 + b._2))
        else (0, 0)
      }.reduce((a, b) => (a._1 + b._1, a._2 + b._2))
      val fp = revTags.map { info =>
        if (info._1 == 1) {
          info._2.map(o => if (o._1 == 0) 1 else 0).sum
        } else 0
      }.sum
      2.0 * tp / (2 * tp + fp + fn)
    } else {
      val tpfns = tags.map { info =>
        val (tp, fn) = info._2.map(o => if (o == info._1) (1, 0) else (0, 1))
          .reduce((a, b) => (a._1 + b._1, a._2 + b._2))
        (info._1, (tp, fn))
      }
      val fps = revTags.map { info =>
        val fp = info._2.map(t => if (t._1 != info._1) 1 else 0).sum
        (info._1, fp)
      }

      tpfns.leftOuterJoin(fps).map { it =>
        val tfp = it._2._2
        tfp match {
          case Some(tfp) =>
            val tmp = 2 * it._2._1._1 + it._2._1._2 + tfp
            if (tmp == 0) 0.0 else 2.0 * it._2._1._1 / tmp
          case None =>
            val tmp = 2 * it._2._1._1 + it._2._1._2
            if (tmp == 0) 0.0 else 2.0 * it._2._1._1 / tmp
        }
      }.sum / classNum
    }
    tags.unpersist(blocking = false)
    revTags.unpersist(blocking = false)
    f1
  }

  def calculate1(pairs: RDD[(Double, Double)]): Double = {
    pairs.cache()
    val classNum = pairs.groupBy(f => f._1).count().toInt
    if (classNum == 2) {
      val tp = pairs.filter(f => f._1 == f._2 && f._1 == 1).count()
      val fp = pairs.filter(f => f._1 == 0 && f._2 == 1).count()
      val fn = pairs.filter(f => f._1 == 1 && f._2 == 0).count()
      2.0 * tp / (2 * tp + fp + fn)
    } else {
      (0 until classNum).map { i =>
        val tp = pairs.filter(f => f._1 == f._2 && f._1 == i).count()
        val fp = pairs.filter(f => f._1 != i && f._2 == i).count()
        val fn = pairs.filter(f => f._1 == i && f._2 != i).count()
        2.0 * tp / (2 * tp + fp + fn)
      }.sum / classNum
    }
  }
}
