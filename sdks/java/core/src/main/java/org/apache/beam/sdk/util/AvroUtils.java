/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.util;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.avro.file.DataFileConstants;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A set of utilities for working with Avro files.
 *
 * <p>These utilities are based on the <a
 * href="https://avro.apache.org/docs/1.7.7/spec.html">Avro 1.7.7</a> specification.
 */
public class AvroUtils {

  /**
   * Avro file metadata.
   */
  public static class AvroMetadata {
    private byte[] syncMarker;
    private String codec;
    private String schemaString;

    AvroMetadata(byte[] syncMarker, String codec, String schemaString) {
      this.syncMarker = syncMarker;
      this.codec = codec;
      this.schemaString = schemaString;
    }

    /**
     * The JSON-encoded <a href="https://avro.apache.org/docs/1.7.7/spec.html#schemas">schema</a>
     * string for the file.
     */
    public String getSchemaString() {
      return schemaString;
    }

    /**
     * The <a href="https://avro.apache.org/docs/1.7.7/spec.html#Required+Codecs">codec</a> of the
     * file.
     */
    public String getCodec() {
      return codec;
    }

    /**
     * The 16-byte sync marker for the file.  See the documentation for
     * <a href="https://avro.apache.org/docs/1.7.7/spec.html#Object+Container+Files">Object
     * Container File</a> for more information.
     */
    public byte[] getSyncMarker() {
      return syncMarker;
    }
  }

  /**
   * Reads the {@link AvroMetadata} from the header of an Avro file.
   *
   * <p>This method parses the header of an Avro
   * <a href="https://avro.apache.org/docs/1.7.7/spec.html#Object+Container+Files">
   * Object Container File</a>.
   *
   * @throws IOException if the file is an invalid format.
   */
  public static AvroMetadata readMetadataFromFile(String fileName) throws IOException {
    String codec = null;
    String schemaString = null;
    byte[] syncMarker;
    try (InputStream stream =
        Channels.newInputStream(IOChannelUtils.getFactory(fileName).open(fileName))) {
      BinaryDecoder decoder = DecoderFactory.get().binaryDecoder(stream, null);

      // The header of an object container file begins with a four-byte magic number, followed
      // by the file metadata (including the schema and codec), encoded as a map. Finally, the
      // header ends with the file's 16-byte sync marker.
      // See https://avro.apache.org/docs/1.7.7/spec.html#Object+Container+Files for details on
      // the encoding of container files.

      // Read the magic number.
      byte[] magic = new byte[DataFileConstants.MAGIC.length];
      decoder.readFixed(magic);
      if (!Arrays.equals(magic, DataFileConstants.MAGIC)) {
        throw new IOException("Missing Avro file signature: " + fileName);
      }

      // Read the metadata to find the codec and schema.
      ByteBuffer valueBuffer = ByteBuffer.allocate(512);
      long numRecords = decoder.readMapStart();
      while (numRecords > 0) {
        for (long recordIndex = 0; recordIndex < numRecords; recordIndex++) {
          String key = decoder.readString();
          // readBytes() clears the buffer and returns a buffer where:
          // - position is the start of the bytes read
          // - limit is the end of the bytes read
          valueBuffer = decoder.readBytes(valueBuffer);
          byte[] bytes = new byte[valueBuffer.remaining()];
          valueBuffer.get(bytes);
          if (key.equals(DataFileConstants.CODEC)) {
            codec = new String(bytes, "UTF-8");
          } else if (key.equals(DataFileConstants.SCHEMA)) {
            schemaString = new String(bytes, "UTF-8");
          }
        }
        numRecords = decoder.mapNext();
      }
      if (codec == null) {
        codec = DataFileConstants.NULL_CODEC;
      }

      // Finally, read the sync marker.
      syncMarker = new byte[DataFileConstants.SYNC_SIZE];
      decoder.readFixed(syncMarker);
    }
    return new AvroMetadata(syncMarker, codec, schemaString);
  }

  /**
   * Formats BigQuery seconds-since-epoch into String matching JSON export. Thread-safe and
   * immutable.
   */
  private static final DateTimeFormatter DATE_AND_SECONDS_FORMATTER =
      DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZoneUTC();
  // Package private for BigQueryTableRowIterator to use.
  static String formatTimestamp(String timestamp) {
    // timestamp is in "seconds since epoch" format, with scientific notation.
    // e.g., "1.45206229112345E9" to mean "2016-01-06 06:38:11.123456 UTC".
    // Separate into seconds and microseconds.
    double timestampDoubleMicros = Double.parseDouble(timestamp) * 1000000;
    long timestampMicros = (long) timestampDoubleMicros;
    long seconds = timestampMicros / 1000000;
    int micros = (int) (timestampMicros % 1000000);
    String dayAndTime = DATE_AND_SECONDS_FORMATTER.print(seconds * 1000);

    // No sub-second component.
    if (micros == 0) {
      return String.format("%s UTC", dayAndTime);
    }

    // Sub-second component.
    int digits = 6;
    int subsecond = micros;
    while (subsecond % 10 == 0) {
      digits--;
      subsecond /= 10;
    }
    String formatString = String.format("%%0%dd", digits);
    String fractionalSeconds = String.format(formatString, subsecond);
    return String.format("%s.%s UTC", dayAndTime, fractionalSeconds);
  }

  /**
   * Utility function to convert from an Avro {@link GenericRecord} to a BigQuery {@link TableRow}.
   *
   * See <a href="https://cloud.google.com/bigquery/exporting-data-from-bigquery#config">
   * "Avro format"</a> for more information.
   */
  public static TableRow convertGenericRecordToTableRow(GenericRecord record, TableSchema schema) {
    return convertGenericRecordToTableRow(record, schema.getFields());
  }

  private static TableRow convertGenericRecordToTableRow(
      GenericRecord record, List<TableFieldSchema> fields) {
    TableRow row = new TableRow();
    for (TableFieldSchema subSchema : fields) {
      // Per https://cloud.google.com/bigquery/docs/reference/v2/tables#schema, the name field
      // is required, so it may not be null.
      Field field = record.getSchema().getField(subSchema.getName());
      Object convertedValue =
          getTypedCellValue(field.schema(), subSchema, record.get(field.name()));
      if (convertedValue != null) {
        // To match the JSON files exported by BigQuery, do not include null values in the output.
        row.set(field.name(), convertedValue);
      }
    }
    return row;
  }

  @Nullable
  private static Object getTypedCellValue(Schema schema, TableFieldSchema fieldSchema, Object v) {
    // Per https://cloud.google.com/bigquery/docs/reference/v2/tables#schema, the mode field
    // is optional (and so it may be null), but defaults to "NULLABLE".
    String mode = firstNonNull(fieldSchema.getMode(), "NULLABLE");
    switch (mode) {
      case "REQUIRED":
        return convertRequiredField(schema.getType(), fieldSchema, v);
      case "REPEATED":
        return convertRepeatedField(schema, fieldSchema, v);
      case "NULLABLE":
        return convertNullableField(schema, fieldSchema, v);
      default:
        throw new UnsupportedOperationException(
            "Parsing a field with BigQuery field schema mode " + fieldSchema.getMode());
    }
  }

  private static List<Object> convertRepeatedField(
      Schema schema, TableFieldSchema fieldSchema, Object v) {
    Type arrayType = schema.getType();
    verify(
        arrayType == Type.ARRAY,
        "BigQuery REPEATED field %s should be Avro ARRAY, not %s",
        fieldSchema.getName(),
        arrayType);
    // REPEATED fields are represented as Avro arrays.
    if (v == null) {
      // Handle the case of an empty repeated field.
      return ImmutableList.of();
    }
    @SuppressWarnings("unchecked")
    List<Object> elements = (List<Object>) v;
    ImmutableList.Builder<Object> values = ImmutableList.builder();
    Type elementType = schema.getElementType().getType();
    for (Object element : elements) {
      values.add(convertRequiredField(elementType, fieldSchema, element));
    }
    return values.build();
  }

  private static Object convertRequiredField(
      Type avroType, TableFieldSchema fieldSchema, Object v) {
    // REQUIRED fields are represented as the corresponding Avro types. For example, a BigQuery
    // INTEGER type maps to an Avro LONG type.
    checkNotNull(v, "REQUIRED field %s should not be null", fieldSchema.getName());
    ImmutableMap<String, Type> fieldMap =
        ImmutableMap.<String, Type>builder()
            .put("STRING", Type.STRING)
            .put("INTEGER", Type.LONG)
            .put("FLOAT", Type.DOUBLE)
            .put("BOOLEAN", Type.BOOLEAN)
            .put("TIMESTAMP", Type.LONG)
            .put("RECORD", Type.RECORD)
            .build();
    // Per https://cloud.google.com/bigquery/docs/reference/v2/tables#schema, the type field
    // is required, so it may not be null.
    String bqType = fieldSchema.getType();
    Type expectedAvroType = fieldMap.get(bqType);
    verify(
        avroType == expectedAvroType,
        "Expected Avro schema type %s, not %s, for BigQuery %s field %s",
        expectedAvroType,
        avroType,
        bqType,
        fieldSchema.getName());
    switch (fieldSchema.getType()) {
      case "STRING":
        // Avro will use a CharSequence to represent String objects, but it may not always use
        // java.lang.String; for example, it may prefer org.apache.avro.util.Utf8.
        verify(v instanceof CharSequence, "Expected CharSequence (String), got %s", v.getClass());
        return v.toString();
      case "INTEGER":
        verify(v instanceof Long, "Expected Long, got %s", v.getClass());
        return ((Long) v).toString();
      case "FLOAT":
        verify(v instanceof Double, "Expected Double, got %s", v.getClass());
        return v;
      case "BOOLEAN":
        verify(v instanceof Boolean, "Expected Boolean, got %s", v.getClass());
        return v;
      case "TIMESTAMP":
        // TIMESTAMP data types are represented as Avro LONG types. They are converted back to
        // Strings with variable-precision (up to six digits) to match the JSON files export
        // by BigQuery.
        verify(v instanceof Long, "Expected Long, got %s", v.getClass());
        Double doubleValue = ((Long) v) / 1000000.0;
        return formatTimestamp(doubleValue.toString());
      case "RECORD":
        verify(v instanceof GenericRecord, "Expected GenericRecord, got %s", v.getClass());
        return convertGenericRecordToTableRow((GenericRecord) v, fieldSchema.getFields());
      default:
        throw new UnsupportedOperationException(
            String.format(
                "Unexpected BigQuery field schema type %s for field named %s",
                fieldSchema.getType(),
                fieldSchema.getName()));
    }
  }

  @Nullable
  private static Object convertNullableField(
      Schema avroSchema, TableFieldSchema fieldSchema, Object v) {
    // NULLABLE fields are represented as an Avro Union of the corresponding type and "null".
    verify(
        avroSchema.getType() == Type.UNION,
        "Expected Avro schema type UNION, not %s, for BigQuery NULLABLE field %s",
        avroSchema.getType(),
        fieldSchema.getName());
    List<Schema> unionTypes = avroSchema.getTypes();
    verify(
        unionTypes.size() == 2,
        "BigQuery NULLABLE field %s should be an Avro UNION of NULL and another type, not %s",
        fieldSchema.getName(),
        unionTypes);

    if (v == null) {
      return null;
    }

    Type firstType = unionTypes.get(0).getType();
    if (!firstType.equals(Type.NULL)) {
      return convertRequiredField(firstType, fieldSchema, v);
    }
    return convertRequiredField(unionTypes.get(1).getType(), fieldSchema, v);
  }
}
