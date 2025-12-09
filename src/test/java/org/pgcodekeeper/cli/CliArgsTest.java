/*******************************************************************************
 * Copyright 2017-2025 TAXTELECOM, LLC
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.kohsuke.args4j.CmdLineException;

class CliArgsTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "--mode PARSE;" +
                    "Please specify SOURCE.",

            "--mode PARSE -o;" +
                    "Option \"-o (--output)\" takes an operand",

            "--mode PARSE -s filename -t filename -o filename;" +
                    "-t (--target) cannot be used with --mode PARSE option",

            "--mode graph --graph-name public.test jdbc:postgresql:q jdbc:postgresql:q2;"
                    + "-t (--target) cannot be used with --mode GRAPH option",

            "--mode graph --graph-name test;"
                    + "Please specify SOURCE.",

            "--mode PARSE --graph-filter-object COLUMN jdbc:postgresql:q;"
                    + "--graph-filter-object cannot be used with --mode PARSE option",

            "jdbc:postgresql:q;"
                    + "Please specify both SOURCE and DEST.",

            "jdbc:postgresql:q jdbc:postgresql:q2 -X -C;"
                    + "-C (--concurrently-mode) cannot be used with the option(s) -X (--add-transaction) for PostgreSQL.",

            "-r filename filename;"
                    + "Script can be applied only to database.",

            "-R filename filename filename;"
                    + "Option -R (--run-on) must specify JDBC connection string.",

            "filename filename --simplify-views --db-type MS;"
                    + "--simplify-views cannot be used with --db-type MS option",

    })
    void badArgsTest(String arguments, String message) {
        String[] args = arguments.split(" ");
        CliArgs cliArgs = new CliArgs();
        try {
            cliArgs.parse(args);
            Assertions.fail();
        } catch (CmdLineException e) {
            Assertions.assertEquals(message, e.getMessage());
        }
    }
}
