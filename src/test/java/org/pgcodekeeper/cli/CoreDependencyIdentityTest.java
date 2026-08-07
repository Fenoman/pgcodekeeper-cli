/*
 * Copyright 2015-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pgcodekeeper.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class CoreDependencyIdentityTest {

    private static final String CORE_PROPERTIES =
            "META-INF/maven/org.pgcodekeeper/pgcodekeeper-core/pom.properties";
    private static final String PACKED_CACHE_MARKER =
            "org/pgcodekeeper/core/database/pg/jdbc/PgCatalogReaderPackStore.class";

    @Test
    void usesNeoCoreWithPackedCatalogCache() throws IOException {
        ClassLoader loader = CoreDependencyIdentityTest.class.getClassLoader();
        Properties coordinates = new Properties();

        try (InputStream in = loader.getResourceAsStream(CORE_PROPERTIES)) {
            assertNotNull(in, "Missing pgcodekeeper-core Maven identity");
            coordinates.load(in);
        }

        assertEquals("15.1.0-neo1", coordinates.getProperty("version"),
                "Unexpected pgcodekeeper-core version");
        assertNotNull(loader.getResource(PACKED_CACHE_MARKER),
                "Packed PostgreSQL catalog cache is absent from pgcodekeeper-core");
    }
}
