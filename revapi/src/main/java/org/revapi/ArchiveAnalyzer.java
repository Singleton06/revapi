/*
 * Copyright 2014-2017 Lukas Krejci
 * and other contributors as indicated by the @author tags.
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
 */
package org.revapi;

import javax.annotation.Nonnull;

/**
 * The instances of implementations of this interface are produced by the {@link org.revapi.ApiAnalyzer}s to
 * analyze the API archives and create an element tree that is then used for API comparison.
 *
 * @author Lukas Krejci
 * @since 0.1
 */
public interface ArchiveAnalyzer {

    /**
     * Analyzes the API archives and filters the forest using the provided filter.
     *
     * @param filter the filter to use to "prune" the forest
     * @return the element forest ready for analysis
     */
    @Nonnull
    ElementForest analyze(Filter filter);

    /**
     * Implementation of this interface will be provided to the archive analyzer so that it can filter out elements
     * that the user explicitly does/doesn't want included in the analysis.
     */
    interface Filter {
        FilterResult filter(Element element);
    }
}
