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

import org.pgcodekeeper.core.utils.FileUtils;
import org.pgcodekeeper.core.utils.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class ArgumentsProvider implements AutoCloseable {

    protected static final String STANDALONE = "pgcodekeeper_standalone_";
    protected static final Path TEMP_DIR = Paths.get(System.getProperty("java.io.tmpdir"));

    protected final String resName;
    protected Path resFile;
    protected TempDir resDir;

    protected ArgumentsProvider() {
        this(null);
    }

    protected ArgumentsProvider(String resName) {
        this.resName = resName;
    }

    protected abstract String[] args() throws URISyntaxException, IOException;

    /**
     * Names the case in the surefire report. The inherited {@code Object.toString()}
     * carries an identity hash, which makes the reported test name differ on every
     * run once phrased test case names are enabled.
     */
    @Override
    public String toString() {
        String name = getClass().getSimpleName();
        return resName == null ? name : name + '(' + resName + ')';
    }

    public String output() {
        return "";
    }

    public Path getPredefinedResultFile() throws URISyntaxException, IOException {
        return getFile(FILES_POSTFIX.DIFF_SQL);
    }

    protected final Path getFile(FILES_POSTFIX postfix) throws URISyntaxException, IOException {
        return getFile(postfix, resName);
    }

    protected final Path getFile(FILES_POSTFIX postfix, String resName) throws URISyntaxException, IOException{
        return TestUtils.getPathToResource(this.getClass(), resName + postfix);
    }

    public Path getDiffResultFile() throws IOException {
        if (resFile == null) {
            resFile = FileUtils.createTempFile(STANDALONE, "");
        }

        return resFile;
    }

    public String getDiffFileContents() throws IOException {
        return Files.readString(getDiffResultFile());
    }

    public TempDir getParseResultDir() throws IOException {
        if (resDir == null) {
            resDir = new TempDir(TEMP_DIR, STANDALONE);
        }

        return resDir;
    }

    @Override
    public void close() {
        try {
            if (resFile != null && !Files.isDirectory(resFile)) {
                Files.deleteIfExists(resFile);
            }
        } catch (Exception e) {
            // do nothing
        }

        try {
            if (resDir != null) {
                resDir.close();
            }
        } catch (Exception e) {
            // do nothing
        }
    }
}