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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * What the CLI says when a run stops for a reason the collected loader errors
 * do not explain.
 * <p>
 * Every mode funnels its {@link IllegalStateException} into
 * {@code Application.printError}, and there are two very different kinds. One
 * is {@link LoadErrorsException}: the load finished with errors, those errors
 * are the whole account, and the exception itself says nothing the list does
 * not. The other is a failure wrapped on its way out of the core - a catalog
 * reader that died, a wrapped {@code OutOfMemoryError}, an invariant a loader
 * tripped over - and for those the exception is the only account there is.
 * <p>
 * Before this class the second kind was dropped whenever the first kind's list
 * happened to be non-empty, and the caller returned false immediately after,
 * so the cause never reached the outer handler either. An operator holding a
 * project with one syntax error in it and a JDBC side that crashed was told
 * about the syntax error, and only about the syntax error.
 */
class PrintErrorCauseTest {

    private static final String PARSE_ERROR = "project.sql line 3:14 mismatched input ')'";
    private static final String REAL_FAILURE = "Unexpected parallel catalog reader failure";

    /**
     * The defect itself. The list is not empty, and the reason the run stopped
     * is not in it.
     */
    @Test
    void aWrappedFailureIsReportedEvenWhenLoadErrorsWereCollected() {
        var cause = new IllegalStateException(REAL_FAILURE, new RuntimeException("connection reset"));

        String output = capture(List.of(PARSE_ERROR), cause);

        Assertions.assertTrue(output.contains(PARSE_ERROR),
                () -> "the collected errors must still be reported, got:\n" + output);
        Assertions.assertTrue(output.contains(REAL_FAILURE),
                () -> "and so must the failure that actually stopped the run, got:\n" + output);
    }

    /**
     * The other side, and the reason this is not simply "print everything": the
     * load-errors signal carries no diagnostic of its own, so repeating it
     * under the very errors it stands for is noise. Both halves have to be
     * pinned or a fix could satisfy one by breaking the other.
     */
    @Test
    void theLoadErrorsSignalItselfAddsNothingToTheErrorsItStandsFor() {
        var cause = new LoadErrorsException("Error while load database");

        String output = capture(List.of(PARSE_ERROR), cause);

        Assertions.assertEquals(PARSE_ERROR + "\n", output,
                "the collected errors are the whole account of a load that reported them");
    }

    /**
     * The contract that was already there and must survive: with nothing
     * collected, the cause is the only diagnostic and is always printed -
     * including a {@link LoadErrorsException}, so that no path can end in
     * total silence.
     */
    @Test
    void withNothingCollectedTheCauseIsAlwaysReported() {
        Assertions.assertTrue(capture(List.of(), new IllegalStateException(REAL_FAILURE)).contains(REAL_FAILURE),
                "an empty list leaves the cause as the only diagnostic");
        Assertions.assertFalse(capture(List.of(), new LoadErrorsException("Error while load database")).isEmpty(),
                "and no branch of this method may print nothing at all");
    }

    private static String capture(List<Object> errors, Throwable cause) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             PrintStream ps = new PrintStream(baos)) {
            System.setOut(ps);
            System.setErr(ps);

            Application.printError(errors, cause);

            System.out.flush();
            return baos.toString().replace("\r\n", "\n");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }
}
