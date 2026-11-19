/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.search.indexaware;

import org.opensearch.core.action.ActionListener;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.search.builder.SearchSourceBuilder;

/**
 * Extension point for plugins to act on a search request with per-index mapping context, before the
 * coordinator query rewrite &mdash; the same kind of thing a search pipeline request processor does, but
 * driven by index and field configuration rather than by a pipeline attached to the request.
 * <p>
 * Registered via {@link SearchPlugin#getIndexAwareSearchRequestProcessors()}, and
 * only runs at all when {@code search.index_aware_search_request_processors.enabled} is set. Each
 * registered processor then runs once per request, on the coordinator node, after any search pipeline
 * request processors have run and before the normal coordinator query rewrite ({@code Rewriteable.rewrite})
 * &mdash; so a processor's output (for example a composite query such as {@code hybrid}) still fans out and
 * rewrites normally afterward.
 * <p>
 * Unlike a search pipeline request processor, this hook:
 * <ul>
 *   <li>sees the concrete local indices this request targets and their current {@code IndexMetadata}
 *       (including mappings), via {@link IndexAwareContext}, so it can be correct per index
 *       (for example across an alias where one index has a feature mapped and another doesn't);</li>
 *   <li>always runs, regardless of which (or no) search pipeline is attached to the request, and can't
 *       be bypassed with {@code ?search_pipeline=_none};</li>
 *   <li>can run before the whole search pipeline instead of after it, via {@link #getExecutionStage()}.</li>
 * </ul>
 * Not every implementation rewrites the query: a processor may instead act purely on {@code size},
 * {@code from}, aggregations, or other source fields, the same way some pipeline request processors do
 * today &mdash; {@code process} just happens to need to resolve the (possibly unchanged) source either way.
 * <p>
 * <b>Internal searches.</b> By default, a processor doesn't run for a search that
 * {@link IndexAwareSearchRequestScope#isInternalByDefault} classifies as internal &mdash; for example, the
 * initial search of a {@code _delete_by_query}/{@code _update_by_query}/{@code _reindex} job, whose resolved
 * shard contexts are reused by later batches, so a rewrite that changes which documents match would change
 * which documents the job deletes/updates. A processor confident its rewrite is safe for such cases must opt
 * in via {@link #appliesToInternalSearches()}.
 * <p>
 * <b>Failures fail the search.</b> A processor that throws, or resolves its listener with a failure, fails the
 * search with that exception; the remaining processors and the search itself don't run. A processor here can
 * change what a query means, so skipping it silently would return results for a different query than the one
 * the index is configured to run, and would do so intermittently if the cause is a flaky dependency. A
 * processor whose absence only makes results less good, not wrong, can opt out with {@link #ignoreFailure()}.
 * <p>
 * <b>Asynchronous by design, not blocking.</b> {@code process} resolves its result through a listener
 * rather than returning it directly, the same shape {@code Rewriteable.rewriteAndFetch} and
 * {@code QueryRewriteContext#registerAsyncAction} already use for coordinator-rewrite work that needs to
 * wait on something (a {@code terms} lookup, a template query). An earlier synchronous-return version of
 * this method was dispatched on {@code ThreadPool.Names.SEARCH} on the theory that was a safe pool to
 * block on. It wasn't: that pool also runs the shard query phase, so a processor blocking on a sub-search
 * (for example, a model lookup that itself searches an index) could hold the one thread its own sub-search
 * needed to complete, deadlocking under load -- reproduced directly by running this hook's own
 * {@code IndexAwareSearchRequestProcessorIT#testProcessorDoesNotReprocessASubSearchItIssues} against a
 * one-thread search pool, which hung. Changing this method's signature after a consumer (neural-search, for
 * ASE) depends on it would be a breaking change, so it's done now, before that happens, rather than as a
 * later "Phase 2."
 *
 * @opensearch.internal
 */
public interface IndexAwareSearchRequestProcessor {

    /**
     * Act on a search request's source, resolving {@code listener} with the result.
     * <p>
     * A processor that needs to wait on something (a model lookup, a sub-search) does so without blocking
     * any thread: register the wait, and call {@code listener.onResponse} from whatever callback the
     * underlying asynchronous call completes on. A processor with nothing to wait on may call
     * {@code listener.onResponse(source)} synchronously, inline, before returning.
     * <p>
     * May resolve {@code source} mutated in place, rather than a new instance; the dispatch loop compares
     * by identity only to decide whether to re-attach the result to the request, which a same-reference,
     * mutated return satisfies for free. The {@code source} this processor receives is already a defensive
     * copy the dispatch loop made before calling the first processor, not the caller's own
     * {@code SearchRequest}'s source, so mutating it in place can't leak back into a {@code SearchRequest}
     * instance the caller might reuse or retry.
     * <p>
     * {@code listener} must be resolved exactly once; a second resolution is ignored. A processor that
     * never resolves it leaves the search waiting until the search task is cancelled, so every path out of
     * this method, including every failure path of anything it waits on, must end in the listener.
     * <p>
     * The thread context this method is called with is restored before the search continues, whichever
     * thread and context {@code listener} is resolved from.
     * <p>
     * A processor that throws, or resolves {@code listener} with a failure, fails the search with that
     * exception, unless it opts out via {@link #ignoreFailure()}.
     *
     * @param source   the search request's source, after any search pipeline request processors (and any
     *                 earlier {@code IndexAwareSearchRequestProcessor} in this request) have run
     * @param context  resolved local target indices and their metadata (including current mappings)
     * @param listener resolved with the (possibly modified) source, or {@code source} unchanged if this
     *                 processor doesn't apply to this request
     */
    void process(SearchSourceBuilder source, IndexAwareContext context, ActionListener<SearchSourceBuilder> listener);

    /**
     * A cheap check, on the request's source alone, of whether this processor could have anything to do for
     * this request: for example, whether the source has a query at all, or the kind of clause this
     * processor rewrites. Runs on every search before anything else the hook does. If every registered
     * processor returns {@code false}, the request skips the hook entirely: no index resolution, no
     * mapping lookups, no copy of the source, and the request keeps its original {@code QueryBuilder}
     * instances.
     * <p>
     * {@code source} here is the request's own source, not the copy {@link #process} receives: it must not
     * be modified. Defaults to {@code true}; a processor that can rule most requests out by looking at the
     * source should override it, since that is what keeps the hook's cost off requests it doesn't apply to.
     */
    default boolean mayApplyTo(SearchSourceBuilder source) {
        return true;
    }

    /**
     * Whether the search should carry on without this processor when it fails, instead of failing.
     * Defaults to {@code false}: a processor that changes what a query means must not be skipped silently,
     * or the same request returns differently-meant results depending on whether a dependency happened to
     * be healthy. Override only for a processor whose absence leaves results still correct, just less good.
     * <p>
     * When {@code true}, a failure is logged (rate limited) and the remaining processors run against the
     * source this processor was given, so a processor that opts in must not have modified that source in
     * place before failing. Cancellation, circuit breaker and thread pool rejection failures always fail
     * the search regardless: those say the node can't do the work, not that this processor is broken.
     */
    default boolean ignoreFailure() {
        return false;
    }

    /**
     * Whether this processor should still run for a search that
     * {@link IndexAwareSearchRequestScope#isInternalByDefault} classifies as internal. Defaults to
     * {@code false}: most rewrites aren't safe to apply blindly to, for example, the initial search of a
     * {@code _reindex} job. Override to opt in if this processor's rewrite is safe for those cases.
     * <p>
     * One flag covers two unrelated reasons {@code isInternalByDefault} can return {@code true} &mdash; a
     * {@code BulkByScrollTask} parent (by-query/reindex document selection) and a system-index target
     * (housekeeping reads) &mdash; so a processor can't opt into one without the other. Splitting them
     * needs {@link IndexAwareContext} to carry which reason applied, which it doesn't yet; see the
     * companion RFC.
     */
    default boolean appliesToInternalSearches() {
        return false;
    }

    /**
     * A name for this processor, used only for logging a processor's own ignored failure and (in a future,
     * cross-plugin ordering mechanism still under discussion on the companion RFC) for attribution and
     * ordering. Defaults to the implementing class's simple name, which is useless for a lambda; a
     * processor that cares about being identifiable in logs or ordering should override this with
     * something stable.
     */
    default String getName() {
        return getClass().getSimpleName();
    }

    /**
     * Where this processor runs relative to the search pipeline (the same user-configured pipeline,
     * including its system-generated pre/post stages, that {@code SearchRequestProcessor}s run in).
     * Defaults to {@link ExecutionStage#AFTER_PIPELINE}, matching this hook's original (and still most
     * common) position: after any search pipeline request processors have run, and before the normal
     * coordinator query rewrite.
     * <p>
     * Mirrors {@code SystemGeneratedProcessor#getExecutionStage()}'s {@code PRE_USER_DEFINED}/
     * {@code POST_USER_DEFINED} shape -- the parity this extension point is meant to have with
     * system-generated search processors -- but is a separate enum, not a shared one: the two mechanisms
     * are deliberately independent (see the companion RFC), and this hook's "before" position is relative
     * to the whole pipeline (every stage SGSP itself can occupy), not just the user's own part of it.
     * <p>
     * A {@link ExecutionStage#BEFORE_PIPELINE} processor's {@link IndexAwareContext} is resolved against
     * the request as it existed before the pipeline ran; if a pipeline processor later changes which
     * indices the request targets, a before-pipeline processor's view of the target indices can disagree
     * with what the request actually executes against. No shipped search pipeline processor does this
     * today, but the interface doesn't forbid it.
     */
    default ExecutionStage getExecutionStage() {
        return ExecutionStage.AFTER_PIPELINE;
    }

    /**
     * The stage to execute the processor, relative to the search pipeline. See {@link #getExecutionStage()}.
     */
    enum ExecutionStage {
        /** Before the search pipeline (its system-generated pre stage, the user's own pipeline, and its system-generated post stage). */
        BEFORE_PIPELINE,
        /** After the search pipeline; the default. */
        AFTER_PIPELINE,
    }
}
