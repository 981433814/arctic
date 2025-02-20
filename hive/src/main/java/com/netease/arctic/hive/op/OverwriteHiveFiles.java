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
import com.netease.arctic.hive.table.UnkeyedHiveTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotUpdate;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;

import java.util.function.Consumer;

import static com.netease.arctic.op.OverwriteBaseFiles.PROPERTIES_TRANSACTION_ID;

public class OverwriteHiveFiles extends UpdateHiveFiles<OverwriteFiles> implements OverwriteFiles {

  private final OverwriteFiles delegate;

  public OverwriteHiveFiles(Transaction transaction, boolean insideTransaction, UnkeyedHiveTable table,
                            HMSClientPool hmsClient, HMSClientPool transactionClient) {
    super(transaction, insideTransaction, table, hmsClient, transactionClient);
    this.delegate = transaction.newOverwrite();
  }

  @Override
  SnapshotUpdate<?> getSnapshotUpdateDelegate() {
    return delegate;
  }

  @Override
  public OverwriteFiles overwriteByRowFilter(Expression expr) {
    Preconditions.checkArgument(!table.spec().isUnpartitioned() || expr == Expressions.alwaysTrue(),
        "Unpartitioned hive table support alwaysTrue expression only");
    delegate.overwriteByRowFilter(expr);
    this.expr = expr;
    return this;
  }

  @Override
  public OverwriteFiles addFile(DataFile file) {
    delegate.addFile(file);
    String hiveLocationRoot = table.hiveLocation();
    String dataFileLocation = file.path().toString();
    if (dataFileLocation.toLowerCase().contains(hiveLocationRoot.toLowerCase())) {
      // only handle file in hive location
      this.addFiles.add(file);
    }
    return this;
  }

  @Override
  public OverwriteFiles deleteFile(DataFile file) {
    delegate.deleteFile(file);
    String hiveLocation = table.hiveLocation();
    String dataFileLocation = file.path().toString();
    if (dataFileLocation.toLowerCase().contains(hiveLocation.toLowerCase())) {
      // only handle file in hive location
      this.deleteFiles.add(file);
    }
    return this;
  }

  @Override
  public OverwriteFiles validateAddedFilesMatchOverwriteFilter() {
    delegate.validateAddedFilesMatchOverwriteFilter();
    return this;
  }

  @Override
  public OverwriteFiles validateFromSnapshot(long snapshotId) {
    delegate.validateFromSnapshot(snapshotId);
    return this;
  }

  @Override
  public OverwriteFiles caseSensitive(boolean caseSensitive) {
    delegate.caseSensitive(caseSensitive);
    return this;
  }

  @Override
  public OverwriteFiles validateNoConflictingAppends(Expression conflictDetectionFilter) {
    delegate.validateNoConflictingAppends(conflictDetectionFilter);
    return this;
  }

  @Override
  public OverwriteFiles validateNoConflictingAppends(Long readSnapshotId, Expression conflictDetectionFilter) {
    delegate.validateNoConflictingAppends(readSnapshotId, conflictDetectionFilter);
    return this;
  }

  @Override
  public OverwriteFiles set(String property, String value) {
    if (PROPERTIES_TRANSACTION_ID.equals(property)) {
      this.txId = Long.parseLong(value);
    }

    if (PROPERTIES_VALIDATE_LOCATION.equals(property)) {
      this.validateLocation = Boolean.parseBoolean(value);
    }

    delegate.set(property, value);
    return this;
  }

  @Override
  public OverwriteFiles deleteWith(Consumer<String> deleteFunc) {
    delegate.deleteWith(deleteFunc);
    return this;
  }

  @Override
  public OverwriteFiles stageOnly() {
    delegate.stageOnly();
    return this;
  }

  @Override
  public Snapshot apply() {
    return delegate.apply();
  }

  @Override
  public Object updateEvent() {
    return delegate.updateEvent();
  }
}
