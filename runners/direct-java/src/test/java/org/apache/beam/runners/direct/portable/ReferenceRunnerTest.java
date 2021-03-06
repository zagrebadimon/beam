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

package org.apache.beam.runners.direct.portable;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import org.apache.beam.runners.core.construction.JavaReadViaImpulse;
import org.apache.beam.runners.core.construction.PipelineOptionsTranslation;
import org.apache.beam.runners.core.construction.PipelineTranslation;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the {@link ReferenceRunner}. */
@RunWith(JUnit4.class)
public class ReferenceRunnerTest implements Serializable {
  @Test
  public void pipelineExecution() throws Exception {
    Pipeline p = Pipeline.create();
    TupleTag<KV<String, Integer>> food = new TupleTag<>();
    TupleTag<Integer> originals = new TupleTag<Integer>() {};
    PCollectionTuple parDoOutputs =
        p.apply(Create.of(1, 2, 3))
            .apply(
                ParDo.of(
                        new DoFn<Integer, KV<String, Integer>>() {
                          @ProcessElement
                          public void process(@Element Integer e,
                                              MultiOutputReceiver r) {
                            for (int i = 0; i < e; i++) {
                              r.get(food).outputWithTimestamp(
                                  KV.of("foo", e),
                                  new Instant(0).plus(Duration.standardHours(i)));
                            }
                            r.get(originals).output(e);
                          }
                        })
                    .withOutputTags(food, TupleTagList.of(originals)));
    FixedWindows windowFn = FixedWindows.of(Duration.standardMinutes(5L));
    PCollection<KV<String, Set<Integer>>> grouped =
        parDoOutputs
            .get(food)
            .apply(Window.into(windowFn))
            .apply(GroupByKey.create())
            .apply(
                ParDo.of(
                    new DoFn<KV<String, Iterable<Integer>>, KV<String, Set<Integer>>>() {
                      @ProcessElement
                      public void process(@Element KV<String, Iterable<Integer>> e,
                                          OutputReceiver<KV<String, Set<Integer>>> r) {
                        r.output(
                            KV.of(e.getKey(), ImmutableSet.copyOf(e.getValue())));
                      }
                    }));

    PAssert.that(grouped)
        .containsInAnyOrder(
            KV.of("foo", ImmutableSet.of(1, 2, 3)),
            KV.of("foo", ImmutableSet.of(2, 3)),
            KV.of("foo", ImmutableSet.of(3)));

    p.replaceAll(Collections.singletonList(JavaReadViaImpulse.boundedOverride()));

    ReferenceRunner runner =
        ReferenceRunner.forInProcessPipeline(
            PipelineTranslation.toProto(p),
            PipelineOptionsTranslation.toProto(PipelineOptionsFactory.create()));
    runner.execute();
  }

  @Test
  public void testGBK() throws Exception {
    Pipeline p = Pipeline.create();

    PAssert.that(
            p.apply(Create.of(KV.of(42, 0), KV.of(42, 1), KV.of(42, 2)))
                // Will create one bundle for each value, since direct runner uses 1 bundle per key
                .apply(Reshuffle.viaRandomKey())
                // Multiple bundles will emit values onto the same key 42.
                // They must be processed sequentially rather than in parallel, since
                // the trigger firing code expects to receive values sequentially for a key.
                .apply(GroupByKey.create()))
        .satisfies(
            input -> {
              KV<Integer, Iterable<Integer>> kv = Iterables.getOnlyElement(input);
              assertEquals(42, kv.getKey().intValue());
              assertThat(kv.getValue(), containsInAnyOrder(0, 1, 2));
              return null;
            });

    p.replaceAll(Collections.singletonList(JavaReadViaImpulse.boundedOverride()));

    ReferenceRunner runner =
        ReferenceRunner.forInProcessPipeline(
            PipelineTranslation.toProto(p),
            PipelineOptionsTranslation.toProto(PipelineOptionsFactory.create()));
    runner.execute();
  }
}
