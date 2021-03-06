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
package org.apache.beam.sdk.transforms;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.CannotProvideCoderException;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.RunnableOnService;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.values.PCollection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.Serializable;

/**
 * Java 8 Tests for {@link Filter}.
 */
@RunWith(JUnit4.class)
@SuppressWarnings("serial")
public class FilterJava8Test implements Serializable {

  @Rule
  public transient ExpectedException thrown = ExpectedException.none();

  @Test
  @Category(RunnableOnService.class)
  public void testIdentityFilterByPredicate() {
    Pipeline pipeline = TestPipeline.create();

    PCollection<Integer> output = pipeline
        .apply(Create.of(591, 11789, 1257, 24578, 24799, 307))
        .apply(Filter.byPredicate(i -> true));

    PAssert.that(output).containsInAnyOrder(591, 11789, 1257, 24578, 24799, 307);
    pipeline.run();
  }

  @Test
  public void testNoFilterByPredicate() {
    Pipeline pipeline = TestPipeline.create();

    PCollection<Integer> output = pipeline
        .apply(Create.of(1, 2, 4, 5))
        .apply(Filter.byPredicate(i -> false));

    PAssert.that(output).empty();
    pipeline.run();
  }

  @Test
  @Category(RunnableOnService.class)
  public void testFilterByPredicate() {
    Pipeline pipeline = TestPipeline.create();

    PCollection<Integer> output = pipeline
        .apply(Create.of(1, 2, 3, 4, 5, 6, 7))
        .apply(Filter.byPredicate(i -> i % 2 == 0));

    PAssert.that(output).containsInAnyOrder(2, 4, 6);
    pipeline.run();
  }

  /**
   * Confirms that in Java 8 style, where a lambda results in a rawtype, the output type token is
   * not useful. If this test ever fails there may be simplifications available to us.
   */
  @Test
  public void testFilterParDoOutputTypeDescriptorRaw() throws Exception {
    Pipeline pipeline = TestPipeline.create();

    @SuppressWarnings({"unchecked", "rawtypes"})
    PCollection<String> output = pipeline
        .apply(Create.of("hello"))
        .apply(Filter.by(s -> true));

    thrown.expect(CannotProvideCoderException.class);
    pipeline.getCoderRegistry().getDefaultCoder(output.getTypeDescriptor());
  }

  @Test
  @Category(RunnableOnService.class)
  public void testFilterByMethodReference() {
    Pipeline pipeline = TestPipeline.create();

    PCollection<Integer> output = pipeline
        .apply(Create.of(1, 2, 3, 4, 5, 6, 7))
        .apply(Filter.byPredicate(new EvenFilter()::isEven));

    PAssert.that(output).containsInAnyOrder(2, 4, 6);
    pipeline.run();
  }

  private static class EvenFilter implements Serializable {
    public boolean isEven(int i) {
      return i % 2 == 0;
    }
  }
}
