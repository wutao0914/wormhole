/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package edp.wormhole.flinkextension.swifts

import com.alibaba.fastjson.JSONObject
import edp.wormhole.ums.UmsSysField
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.scala.typeutils.Types
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.table.api.Table
import org.apache.flink.table.api.scala.{StreamTableEnvironment, _}
import org.apache.flink.table.expressions.{Expression, ExpressionParser}
import org.apache.flink.types.Row

abstract class AbstractSwiftsProcess(tableEnv: StreamTableEnvironment,
                                     specialConfigObj: JSONObject,
                                     timeCharacteristic: String) extends Serializable {

  protected def buildExpression(preSchemaMap: Map[String, (TypeInformation[_], Int)]): List[Expression] = {
    val originalSchema = preSchemaMap.toList.sortBy(_._2._2).map(_._1)
    if (timeCharacteristic == FlinkxTimeCharacteristicConstants.PROCESSING_TIME) {
      val expressionList = ExpressionParser.parseExpressionList(originalSchema.mkString(",") + s", ${FlinkxTimeCharacteristicConstants.PROCESSING_TIME}.proctime")
      scala.collection.JavaConverters.asScalaIteratorConverter(expressionList.iterator).asScala.toList
    } else {
      val newSchema = originalSchema.updated(preSchemaMap(UmsSysField.TS.toString)._2, UmsSysField.TS.toString + ".rowtime")
      val expressionList = ExpressionParser.parseExpressionList(newSchema.mkString(","))
      scala.collection.JavaConverters.asScalaIteratorConverter(expressionList.iterator).asScala.toList
    }
  }

  protected def covertTable2Stream(table: Table, columnTypes: Array[TypeInformation[_]]): DataStream[Row] = {
    val columnNames = table.getSchema.getFieldNames
    if (null != specialConfigObj && specialConfigObj.containsKey(FlinkxSwiftsConstants.PRESERVE_MESSAGE_FLAG) && specialConfigObj.getBooleanValue(FlinkxSwiftsConstants.PRESERVE_MESSAGE_FLAG)) {
      val columnNamesWithMessageFlag: Array[String] = columnNames ++ Array(FlinkxSwiftsConstants.MESSAGE_FLAG)
      val columnTypesWithMessageFlag: Array[TypeInformation[_]] = columnTypes ++ Array(Types.BOOLEAN)
      val resultDataStream = table.toRetractStream[Row].map(tuple => {
        val rowWithMessageFlag = new Row(columnNames.length + 1)
        for (i <- columnNames.indices) {
          rowWithMessageFlag.setField(i, tuple._2.getField(i))
        }
        rowWithMessageFlag.setField(columnNames.length, tuple._1)
        rowWithMessageFlag
      })(Types.ROW(columnNamesWithMessageFlag, columnTypesWithMessageFlag))
      println(resultDataStream.dataType.toString + "in  doFlinkSql")
      resultDataStream
    } else {
      table.toRetractStream[Row].filter(_._1).map(_._2)(Types.ROW(columnNames, columnTypes))
    }
  }

  protected def rowMapFunc(fieldNames: Array[String], types: Array[TypeInformation[_]]) : TypeInformation[Row] = {
    Types.ROW(fieldNames, types)
  }
}