/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.shortlink.project.mq.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatsMessageRetryPolicyTest {

    @Test
    void shouldRetryBeforeReachingMaxAttempts() {
        assertEquals(StatsMessageRetryPolicy.Action.RETRY, StatsMessageRetryPolicy.nextAction(0, 3));
        assertEquals(StatsMessageRetryPolicy.Action.RETRY, StatsMessageRetryPolicy.nextAction(2, 3));
    }

    @Test
    void shouldMoveToDeadLetterAtMaxAttempts() {
        assertEquals(StatsMessageRetryPolicy.Action.DEAD_LETTER, StatsMessageRetryPolicy.nextAction(3, 3));
    }

    @Test
    void shouldParseInvalidRetryCountAsZero() {
        assertEquals(0, StatsMessageRetryPolicy.retryCount(null));
        assertEquals(0, StatsMessageRetryPolicy.retryCount("invalid"));
        assertEquals(2, StatsMessageRetryPolicy.retryCount("2"));
    }

    @Test
    void shouldLimitErrorMessageLength() {
        String message = "x".repeat(300);

        assertEquals(256, StatsMessageRetryPolicy.errorMessage(message).length());
        assertEquals("unknown error", StatsMessageRetryPolicy.errorMessage(null));
    }
}
