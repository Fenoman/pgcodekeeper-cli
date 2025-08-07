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

import java.nio.file.Path;
import java.nio.file.Paths;

public class Utils {

    private static final String LOG_DIR = ".pgcodekeeper-cli"; //$NON-NLS-1$
    private static final String DEPS_DIR = "dependencies"; //$NON-NLS-1$
    private static final Path META_PATH = Paths.get(System.getProperty("user.home"), Utils.LOG_DIR, Utils.DEPS_DIR); //$NON-NLS-1$

    public static String getVersion() {
        return Utils.class.getPackage().getImplementationVersion();
    }

    public static Path getMetaPath() {
        return META_PATH;
    }

    private Utils() {
    }
}
