/*
 * Copyright (C) 2016/2020 Litote
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jershell.kbson

import kotlinx.serialization.CompositeEncoder
import kotlinx.serialization.ElementValueEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicKind
import kotlinx.serialization.SerialDescriptor
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StructureKind
import kotlinx.serialization.UnionKind
import kotlinx.serialization.internal.PairClassDesc
import kotlinx.serialization.internal.TripleSerializer
import kotlinx.serialization.modules.SerialModule
import org.bson.BsonBinary
import org.bson.BsonWriter
import org.bson.types.Decimal128
import org.bson.types.ObjectId


open class BsonEncoder(
    private val writer: BsonWriter,
    override val context: SerialModule,
    private val configuration: Configuration
) : ElementValueEncoder() {

    private var state = STATE.VALUE
    private var hasBegin = false // for UnionKind
    private var stateMap = StateMap()
    private var deferredKeyName: String? = null

    override fun shouldEncodeElementDefault(desc: SerialDescriptor, index: Int): Boolean = configuration.encodeDefaults

    override fun beginStructure(desc: SerialDescriptor, vararg typeParams: KSerializer<*>): CompositeEncoder {
        when (desc.kind) {
            StructureKind.LIST -> writer.writeStartArray()
            StructureKind.CLASS -> {
                if (hasBegin) {
                    hasBegin = false
                } else {
                    writer.writeStartDocument()
                }
            }
            UnionKind.OBJECT-> {
                if(hasBegin){
                    hasBegin = false
                } else{
                    writer.writeStartDocument()
                }
            }
            is PolymorphicKind -> {
                writer.writeStartDocument()
                writer.writeName(configuration.classDiscriminator)
                hasBegin = true
            }
            StructureKind.MAP -> {
                writer.writeStartDocument()
                stateMap = StateMap()
            }
            else -> throw SerializationException("Primitives are not supported at top-level")
        }
        return super.beginStructure(desc, *typeParams)
    }

    override fun endStructure(desc: SerialDescriptor) {
        when (desc.kind) {
            is StructureKind.LIST -> writer.writeEndArray()
            is StructureKind.MAP, StructureKind.CLASS, UnionKind.OBJECT -> writer.writeEndDocument()
        }
    }

    override fun <T : Any> encodeNullableSerializableValue(serializer: SerializationStrategy<T>, value: T?) {
        when {
            deferredKeyName != null && value == null -> {
                deferredKeyName = null
                // and nothing
            }
            deferredKeyName != null && value != null -> {
                writer.writeName(deferredKeyName)
                deferredKeyName = null
                super.encodeNullableSerializableValue(serializer, value)
            }
            else -> super.encodeNullableSerializableValue(serializer, value)
        }
    }

    override fun encodeElement(desc: SerialDescriptor, index: Int): Boolean {
        when (desc.kind) {
            is StructureKind.CLASS -> {
                val name = desc.getElementName(index)

                // Pair & Triple doesn't have a child description
                if (desc !is PairClassDesc && desc !is TripleSerializer.TripleDesc) {
                    val elemDesc = desc.getElementDescriptor(index)
                    if (elemDesc.isNullable) {
                        val ann =
                            configuration.nonEncodeNull || desc.getElementAnnotations(index).any { it is NonEncodeNull }
                        if (ann) {
                            deferredKeyName = name
                        }
                    }
                }

                if (deferredKeyName == null) {
                    writer.writeName(name)
                }
            }
            is StructureKind.MAP -> {
//                val mapDesc = desc as LinkedHashMapClassDesc
//                if (mapDesc.keyDescriptor !is PrimitiveDescriptor) {
//                    throw SerializationException("map key name is not primitive")
//                }
                state = stateMap.next()
            }
        }
        return true
    }

    override fun encodeNull() {
        writer.writeNull()
    }

    override fun encodeEnum(enumDescription: SerialDescriptor, ordinal: Int) {
        writer.writeString(enumDescription.getElementName(ordinal))
    }

    override fun encodeString(value: String) {
        when (state) {
            STATE.NAME -> encodeStructName(value)
            STATE.VALUE -> writer.writeString(value)
        }
    }

    override fun encodeInt(value: Int) {
        when (state) {
            STATE.VALUE -> writer.writeInt32(value)
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeDouble(value: Double) {
        when (state) {
            STATE.VALUE -> writer.writeDouble(value)
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeFloat(value: Float) {
        when (state) {
            STATE.VALUE -> writer.writeDouble(value.toDouble())
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeLong(value: Long) {
        when (state) {
            STATE.VALUE -> writer.writeInt64(value)
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeChar(value: Char) {
        when (state) {
            STATE.VALUE -> writer.writeSymbol(value.toString())
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeBoolean(value: Boolean) {
        when (state) {
            STATE.VALUE -> writer.writeBoolean(value)
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeByte(value: Byte) {
        when (state) {
            STATE.VALUE -> writer.writeInt32(value.toInt())
            STATE.NAME -> encodeStructName(value)
        }
    }

    override fun encodeUnit() {
        writer.writeNull()
    }

    override fun encodeShort(value: Short) {
        when (state) {
            STATE.VALUE -> writer.writeInt32(value.toInt())
            STATE.NAME -> encodeStructName(value)
        }
    }

    fun encodeDateTime(value: Long) {
        when (state) {
            STATE.VALUE -> writer.writeDateTime(value)
            STATE.NAME -> encodeStructName(value.toString())
        }
    }

    fun encodeObjectId(value: ObjectId) {
        when (state) {
            STATE.VALUE -> writer.writeObjectId(value)
            STATE.NAME -> encodeStructName(value.toString())
        }
    }

    fun encodeDecimal128(value: Decimal128) {
        when (state) {
            STATE.VALUE -> writer.writeDecimal128(value)
            STATE.NAME -> encodeStructName(value.toString())
        }
    }

    fun encodeByteArray(value: ByteArray) {
        when (state) {
            STATE.VALUE -> writer.writeBinaryData(BsonBinary(value))
            // I think we can use base64, but files can be big
            STATE.NAME -> throw SerializationException("ByteArray is not supported as a key of map")
        }
    }

    private fun encodeStructName(value: Any) {
        writer.writeName(value.toString())
        state = STATE.VALUE
    }
}