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

package com.netease.arctic.hive.op;

import com.netease.arctic.hive.HMSClientPool;
import com.netease.arctic.hive.utils.HiveTableUtil;
import com.netease.arctic.table.ArcticTable;
import com.netease.arctic.table.KeyedTable;
import com.netease.arctic.table.TableProperties;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.Schema;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PropertyUtil;

import java.util.Collection;
import java.util.Locale;

/**
 * Schema evolution API implementation for {@link KeyedTable}.
 */
public class HiveSchemaUpdate implements UpdateSchema {
  private final ArcticTable arcticTable;
  private final HMSClientPool hiveClient;
  private final UpdateSchema updateSchema;

  public HiveSchemaUpdate(ArcticTable arcticTable, HMSClientPool hiveClient, UpdateSchema updateSchema) {
    this.arcticTable = arcticTable;
    this.hiveClient = hiveClient;
    this.updateSchema = updateSchema;
  }

  @Override
  public Schema apply() {
    return this.updateSchema.apply();
  }

  @Override
  public void commit() {
    this.updateSchema.commit();
    if (HiveTableUtil.loadHmsTable(hiveClient, arcticTable.id()) == null) {
      throw new RuntimeException(String.format("there is no such hive table named %s", arcticTable.id().toString()));
    }
    syncSchemaToHive();
  }

  private void syncSchemaToHive() {
    org.apache.hadoop.hive.metastore.api.Table tbl = HiveTableUtil.loadHmsTable(hiveClient, arcticTable.id());
    if (tbl == null) {
      throw new RuntimeException(String.format("there is no such hive table named %s", arcticTable.id().toString()));
    }
    tbl.setSd(HiveTableUtil.storageDescriptor(arcticTable.schema(), arcticTable.spec(), tbl.getSd().getLocation(),
        FileFormat.valueOf(PropertyUtil.propertyAsString(arcticTable.properties(), TableProperties.DEFAULT_FILE_FORMAT,
            TableProperties.DEFAULT_FILE_FORMAT_DEFAULT).toUpperCase(Locale.ENGLISH))));
    HiveTableUtil.persistTable(hiveClient, tbl);
  }

  @Override
  public UpdateSchema allowIncompatibleChanges() {
    throw new UnsupportedOperationException("hive table not support allowIncompatibleChanges");
  }

  @Override
  public UpdateSchema addColumn(String name, Type type, String doc) {
    this.updateSchema.addColumn(name, type, doc);
    moveColBeforePar(name);
    return this;
  }

  @Override
  public UpdateSchema addColumn(String parent, String name, Type type, String doc) {
    this.updateSchema.addColumn(parent, name, type, doc);
    if (parent == null) {
      moveColBeforePar(name);
    }
    return this;
  }

  @Override
  public UpdateSchema addRequiredColumn(String name, Type type, String doc) {
    throw new UnsupportedOperationException("hive table not support addRequiredColumn");
  }

  @Override
  public UpdateSchema addRequiredColumn(String parent, String name, Type type, String doc) {
    throw new UnsupportedOperationException("hive table not support addRequiredColumn");
  }

  @Override
  public UpdateSchema renameColumn(String name, String newName) {
    throw new UnsupportedOperationException("not support renameColumn now, there will be error when hive stored as " +
        "parquet and we rename the column");
  }

  @Override
  public UpdateSchema updateColumn(String name, Type.PrimitiveType newType) {
    this.updateSchema.updateColumn(name, newType);
    return this;
  }

  @Override
  public UpdateSchema updateColumnDoc(String name, String newDoc) {
    this.updateSchema.updateColumnDoc(name, newDoc);
    return this;
  }

  @Override
  public UpdateSchema makeColumnOptional(String name) {
    throw new UnsupportedOperationException("hive table not support makeColumnOptional");
  }

  @Override
  public UpdateSchema requireColumn(String name) {
    throw new UnsupportedOperationException("hive table not support requireColumn");
  }

  @Override
  public UpdateSchema deleteColumn(String name) {
    throw new UnsupportedOperationException("hive table not support deleteColumn");
  }

  @Override
  public UpdateSchema moveFirst(String name) {
    throw new UnsupportedOperationException("hive table not support moveFirst");
  }

  @Override
  public UpdateSchema moveBefore(String name, String beforeName) {
    throw new UnsupportedOperationException("hive table not support moveBefore");
  }

  @Override
  public UpdateSchema moveAfter(String name, String afterName) {
    throw new UnsupportedOperationException("hive table not support moveAfter");
  }

  @Override
  public UpdateSchema unionByNameWith(Schema newSchema) {
    throw new UnsupportedOperationException("hive table not support unionByNameWith");
  }

  @Override
  public UpdateSchema setIdentifierFields(Collection<String> names) {
    throw new UnsupportedOperationException("hive table not support setIdentifierFields");
  }

  //It is strictly required that all non-partitioned columns precede partitioned columns in the schema.
  private void moveColBeforePar(String name) {
    if (!arcticTable.spec().isUnpartitioned()) {
      int parFieldMinIndex = Integer.MAX_VALUE;
      Types.NestedField firstParField = null;
      for (PartitionField partitionField : arcticTable.spec().fields()) {
        Types.NestedField sourceField = arcticTable.schema().findField(partitionField.sourceId());
        if (arcticTable.schema().columns().indexOf(sourceField) < parFieldMinIndex) {
          parFieldMinIndex = arcticTable.schema().columns().indexOf(sourceField);
          firstParField = sourceField;
        }
      }
      if (firstParField != null) {
        this.updateSchema.moveBefore(name, firstParField.name());
      }
    }
  }
}
