/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/**
 * Index-aware search request hooks: extension points that let plugins act on a search request with
 * per-index mapping context, before the coordinator query rewrite.
 */
package org.opensearch.search.indexaware;
