package com.datastax.spark.connector.datasource

import com.datastax.spark.connector.ColumnRef
import com.datastax.spark.connector.cql.TableDef
import com.datastax.spark.connector.writer.{RowWriter, RowWriterFactory}
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.types.StructType

class InternalRowWriterFactory(schema: StructType) extends RowWriterFactory[InternalRow] {
  /** Creates a new `RowWriter` instance.
    *
    * @param table           target table the user wants to write into
    * @param selectedColumns columns selected by the user; the user might wish to write only a
    *                        subset of columns */
  override def rowWriter(table: TableDef, selectedColumns: IndexedSeq[ColumnRef]):
  RowWriter[InternalRow] = new InternalRowWriter(schema, table, selectedColumns)

}

/**
  * A [[RowWriter]] that can write SparkSQL `InternalRow", schema defines the
  * structure of InternalRows that will be processed by this writer.
  **/
class InternalRowWriter(
  val schema: StructType,
  val table: TableDef,
  val selectedColumns: IndexedSeq[ColumnRef]) extends RowWriter[InternalRow] {

  override val columnNames = selectedColumns.map(_.columnName)
  val unknownColumnNameSet = columnNames.toSet.diff(table.columnNames.toSet)
  private val typeForSchemaIndex = schema.map(_.dataType).zipWithIndex.map(x => (x._2, x._1)).toMap
  private val columns = columnNames.filter(!unknownColumnNameSet.contains(_)).map(table.columnByName)
  private val schemaRequestedIndexes = {
    val indexedSchema = schema.map(_.name).zipWithIndex.toMap
    selectedColumns.map(column => indexedSchema(column.selectedAs))
  }

  private val dataTypes = schemaRequestedIndexes.map(index => typeForSchemaIndex(index))

  private val convertersToScala = dataTypes.map(CatalystTypeConverters.createToScalaConverter)

  private val convertersToCassandra = columns.map(_.columnType.converterToCassandra)
  private val converters = convertersToScala.zip(convertersToCassandra).map(converterPair =>
    converterPair._1.andThen(converterPair._2.convert(_))
  )
  private val size = selectedColumns.size

  /** Extracts column values from `data` object and writes them into the given buffer
    * in the same order as they are listed in the columnNames sequence. */
  override def readColumnValues(row: InternalRow, buffer: Array[Any]) = {
    var i = 0;
    // Using while loop to avoid allocations in for each
    while (i < size) {
      val colValue = row.get(i, dataTypes(i))
      if (i < converters.size) {
        buffer(i) = converters(i).apply(colValue)
      } else {
        buffer(i) = colValue
      }
      i += 1
    }
  }
}


