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

import java.util.Locale;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Pins the default locale to English before any test class runs. CLI tests
 * assert localized messages in English; relying on the {@link TestUtils}
 * static initializer is load-order dependent and breaks on hosts with a
 * non-English default locale (e.g. ru_RU). Registered via
 * META-INF/services/org.junit.platform.launcher.LauncherSessionListener.
 */
public class EnglishLocaleSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Locale.setDefault(Locale.ENGLISH);
    }
}
