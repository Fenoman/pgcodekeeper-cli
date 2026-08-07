/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.pgcodekeeper.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The top-level handler must produce a readable stderr line for any
 * Throwable, most importantly for Errors such as {@link OutOfMemoryError}
 * that escape the regular {@code catch (Exception)} in
 * {@link Application#process}.
 */
class ApplicationFatalErrorTest {

    @Test
    void formatsOutOfMemoryError() {
        assertEquals(
                "pgcodekeeper-cli: fatal error: java.lang.OutOfMemoryError: Java heap space",
                Application.formatFatalError(new OutOfMemoryError("Java heap space")));
    }

    @Test
    void formatsErrorWithoutMessage() {
        assertEquals("pgcodekeeper-cli: fatal error: java.lang.StackOverflowError",
                Application.formatFatalError(new StackOverflowError()));
    }

    @Test
    void formatsRuntimeException() {
        assertEquals(
                "pgcodekeeper-cli: fatal error: java.lang.IllegalStateException: broken state",
                Application.formatFatalError(new IllegalStateException("broken state")));
    }
}
