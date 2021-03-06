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
package org.apache.beam.sdk.extensions.sql.impl.schema;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.RowCoder;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.Row;

/**
 * {@code BeamPCollectionTable} converts a {@code PCollection<Row>} as a virtual table, then a
 * downstream query can query directly.
 */
public class BeamPCollectionTable extends BaseBeamTable {
  private transient PCollection<Row> upstream;

  public BeamPCollectionTable(PCollection<Row> upstream) {
    super(((RowCoder) upstream.getCoder()).getSchema());
    this.upstream = upstream;
  }

  @Override
  public PCollection<Row> buildIOReader(Pipeline pipeline) {
    return upstream;
  }

  @Override
  public PTransform<? super PCollection<Row>, POutput> buildIOWriter() {
    throw new IllegalArgumentException("cannot use [BeamPCollectionTable] as target");
  }
}
