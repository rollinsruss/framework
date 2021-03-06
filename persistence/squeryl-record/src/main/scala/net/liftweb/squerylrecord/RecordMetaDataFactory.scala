/*
 * Copyright 2010 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb
package squerylrecord

import common.{Box, Full}
import record.{BaseField, MetaRecord, Record, TypedField, OwnedField}
import record.field._

import org.squeryl.internals.{FieldMetaData, PosoMetaData, FieldMetaDataFactory}
import org.squeryl.annotations.Column

import java.lang.reflect.{Method, Field}
import java.lang.annotation.Annotation
import java.sql.{ResultSet, Timestamp}
import java.util.{Calendar, Date}

import scala.collection.immutable.Map

/** FieldMetaDataFactory that allows Squeryl to use Records as model objects. */
class RecordMetaDataFactory extends FieldMetaDataFactory {
  /** Cache MetaRecords by the model object class (Record class) */
  private var metaRecordsByClass: Map[Class[_], MetaRecord[_]] = Map.empty

  /** Given a model object class (Record class) and field name, return the BaseField from the meta record */
  private def findMetaField(clasz: Class[_], name: String): BaseField = {
    def fieldFrom(mr: MetaRecord[_]): BaseField =
      mr.asInstanceOf[Record[_]].fieldByName(name) match {
        case Full(f: BaseField) => f
        case Full(_) => error("field " + name + " in Record metadata for " + clasz + " is not a TypedField")
        case _ => error("failed to find field " + name + " in Record metadata for " + clasz)
      }

    metaRecordsByClass get clasz match {
      case Some(mr) => fieldFrom(mr)
      case None =>
        try {
          val rec = clasz.newInstance.asInstanceOf[Record[_]]
          val mr = rec.meta
          metaRecordsByClass = metaRecordsByClass updated (clasz, mr)
          fieldFrom(mr)
        } catch {
          case ex => error("failed to find MetaRecord for " + clasz + " due to exception " + ex.toString)
        }
    }
  }

  /** Build a Squeryl FieldMetaData for a particular field in a Record */
  def build(parentMetaData: PosoMetaData[_], name: String,
            property: (Option[Field], Option[Method], Option[Method], Set[Annotation]),
            sampleInstance4OptionTypeDeduction: AnyRef, isOptimisticCounter: Boolean): FieldMetaData = {
  	if (!isRecord(parentMetaData.clasz)) {
  		// No Record class, treat it as a normal class in primitive type mode.
  		// This is needed for ManyToMany association classes, for example
  		return SquerylRecord.posoMetaDataFactory.build(parentMetaData, name, property, 
  				sampleInstance4OptionTypeDeduction, isOptimisticCounter)
  	}

    val metaField = findMetaField(parentMetaData.clasz, name)

    val (field, getter, setter, annotations) = property
    val colAnnotation = annotations.find(a => a.isInstanceOf[Column]).map(a => a.asInstanceOf[Column])

    val fieldsValueType = metaField match {
      case (f: SquerylRecordField) => f.classOfPersistentField 
      case (_: BooleanTypedField)  => classOf[Boolean]
      case (_: DateTimeTypedField) => classOf[Timestamp]
      case (_: DoubleTypedField)   => classOf[Double]
      case (_: IntTypedField)      => classOf[java.lang.Integer]
      case (_: LongTypedField)     => classOf[java.lang.Long]
      case (_: DecimalTypedField)     => classOf[BigDecimal]
      case (_: TimeZoneTypedField)   => classOf[String]
      case (_: StringTypedField)   => classOf[String]
      case (_: PasswordTypedField)   => classOf[String]
      case (_: BinaryTypedField)   => classOf[Array[Byte]]
      case (_: LocaleTypedField)   => classOf[String]
      case (_: EnumTypedField[_])   => classOf[Enumeration#Value]
      case (_: EnumNameTypedField[_])   => classOf[Enumeration#Value]
      case _ => error("Unsupported field type. Consider implementing " +
		      "SquerylRecordField for defining the persistent class." +
		      "Field: " + metaField)
    } 

    val overrideColLength = metaField match {
      case (stringTypedField: StringTypedField) => Some(stringTypedField.maxLength)
      case _ => None
    }

    new FieldMetaData(
      parentMetaData,
      name,
      fieldsValueType, // if isOption, this fieldType is the type param of Option, i.e. the T in Option[T]
      fieldsValueType, //in primitive type mode fieldType == wrappedFieldType, in custom type mode wrappedFieldType is the 'real' type, i.e. the (primitive) type that jdbc understands
      None, //val customTypeFactory: Option[AnyRef=>Product1[Any]],
      metaField.optional_?,
      getter,
      setter,
      field,
      colAnnotation,
      isOptimisticCounter,
      metaField) {

      override def length = overrideColLength getOrElse super.length

      private def fieldFor(o: AnyRef) = getter.get.invoke(o).asInstanceOf[TypedField[AnyRef]]

      override def setFromResultSet(target: AnyRef, rs: ResultSet, index: Int) =
        fieldFor(target).setFromAny(Box!!resultSetHandler(rs, index))

      override def get(o: AnyRef) = fieldFor(o).valueBox match {
        case Full(c: Calendar) => new Timestamp(c.getTime.getTime)
        case Full(other) => other
        case _ => null
      }
    }
  }
  
  /**
   * Checks if the given class is a subclass of Record. A special handling is only
   * needed for such subtypes. For other classes, use the standard squeryl methods.
   */
  private def isRecord(clasz: Class[_]) = {
    classOf[Record[_]].isAssignableFrom(clasz)
  }
  

  /**
   * For records, the constructor must not be used directly when
   * constructing Objects. Instead, the createRecord method must be called.
   */
  def createPosoFactory(posoMetaData: PosoMetaData[_]): () => AnyRef = {
  	if (!isRecord(posoMetaData.clasz)) {
  	  // No record class - use standard poso meta data factory
  	  return SquerylRecord.posoMetaDataFactory.createPosoFactory(posoMetaData);
  	}

    // Extract the MetaRecord for the companion object. This
    // is done only once for each class.
    val metaRecord = Class.forName(posoMetaData.clasz.getName +
      "$").getField("MODULE$").get(null).asInstanceOf[MetaRecord[_]]

    () => metaRecord.createRecord.asInstanceOf[AnyRef]
  }
  
  /**
   * There needs to be a special handling for squeryl-record when single fields are selected.
   * 
   * The problem was that record fields reference the record itself and thus Squeryl was of the
   * opinion that the whole record should be returned, as well as the selected field.
   * It is described in detail in this bug report:
   * https://www.assembla.com/spaces/liftweb/tickets/876-record-squeryl-selecting-unspecified-columns-in-generated-sql
   * 
   * By overriding this function, the reference to the record is excluded from
   * the reference finding algorithm in Squeryl.
   */
  override def hideFromYieldInspection(o: AnyRef, f: Field): Boolean = {
    o.isInstanceOf[OwnedField[_]] && isRecord(f.getType)
  }

}
