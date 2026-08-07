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

import java.io.Serial;

/**
 * Signals that a load finished with errors and {@code --ignore-errors} was not
 * given. Carries no diagnostic of its own: the collected errors are the whole
 * account of what went wrong, and {@code Application.printError} prints them.
 * <p>
 * It exists to be told apart from every other {@link IllegalStateException}
 * that reaches the same handler. Those come from the core - a catalog reader
 * that died, a wrapped {@code OutOfMemoryError}, an invariant a loader tripped
 * over - and each of them is the only account there is of why the run stopped.
 * Suppressing them because a parse error happened to be in the list sends the
 * operator to fix a syntax error that was never the reason.
 */
public class LoadErrorsException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 3410902344758012214L;

    /**
     * @param message localized description of the failed load
     */
    public LoadErrorsException(String message) {
        super(message);
    }
}
