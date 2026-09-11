package io.cairn.server;

import io.cairn.core.Model;
import io.cairn.core.ModelVersion;
import io.cairn.core.Registry;
import io.cairn.core.RejectionCode;
import io.cairn.core.Stage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Prometheus text format, rendered from the current state and the engine's counters.
 *
 * <p>Chosen for what an operator needs at three in the morning rather than for what is easy to
 * count. The two that matter most are the ones a healthy-looking registry hides:
 *
 * <ul>
 *   <li><b>{@code cairn_outbox_depth}</b> — effects produced and not yet delivered. A registry
 *       whose outbox is growing is one whose artifacts are not being collected and whose caches are
 *       not being invalidated, and every other metric looks fine while it happens. This is the
 *       number {@code docs/operations.md} says to alert on.
 *   <li><b>{@code cairn_effects_deduplicated_total}</b> — redeliveries a consumer discarded. Not an
 *       error: it counts crashes that landed between a delivery and its acknowledgement, which the
 *       system handles correctly and which nothing else can see. A step change here means something
 *       is restarting.
 * </ul>
 */
final class Metrics {

    private Metrics() {}

    static String render(Registry state, Engine.Stats stats) {
        StringBuilder out = new StringBuilder(2048);

        int versions = 0;
        int tombstones = 0;
        Map<Stage, Integer> byStage = new EnumMap<>(Stage.class);
        for (Model model : state.models().values()) {
            for (ModelVersion version : model.versions().values()) {
                versions++;
                if (version.deleted()) {
                    tombstones++;
                } else {
                    byStage.merge(version.stage(), 1, Integer::sum);
                }
            }
        }
        long artifactBytes = 0;
        int present = 0;
        for (var blob : state.blobs().values()) {
            if (blob.present()) {
                present++;
                artifactBytes += blob.size();
            }
        }

        gauge(out, "cairn_models", "Models with at least one version.", state.models().size());
        gauge(out, "cairn_versions", "Versions, including tombstones.", versions);
        gauge(out, "cairn_tombstones", "Versions that have been deleted.", tombstones);
        gauge(out, "cairn_artifacts", "Artifacts the registry believes are retrievable.", present);
        gauge(out, "cairn_artifact_bytes", "Total size of retrievable artifacts.", artifactBytes);

        out.append("# HELP cairn_versions_by_stage Live versions, by stage.\n");
        out.append("# TYPE cairn_versions_by_stage gauge\n");
        for (Stage stage : Stage.values()) {
            out.append("cairn_versions_by_stage{stage=\"")
                    .append(stage.name().toLowerCase())
                    .append("\"} ")
                    .append(byStage.getOrDefault(stage, 0))
                    .append('\n');
        }

        gauge(out, "cairn_outbox_depth",
                "Effects produced and not yet delivered. Alert on this growing.",
                stats.outboxDepth());
        gauge(out, "cairn_effects_next_sequence",
                "The sequence number the next effect will get.", state.nextEffectSeq());
        gauge(out, "cairn_effects_dispatched_through",
                "Highest effect sequence number known to be applied downstream.",
                state.dispatchedThrough());

        counter(out, "cairn_commands_applied_total",
                "Commands that changed something.", stats.applied());
        counter(out, "cairn_commands_retried_total",
                "Commands accepted as retries of something already done.", stats.retries());
        counter(out, "cairn_effects_delivered_total",
                "Effects handed to the consumer.", stats.effectsDelivered());
        counter(out, "cairn_effects_applied_total",
                "Effects the consumer applied.", stats.effectsApplied());
        counter(out, "cairn_effects_deduplicated_total",
                "Redeliveries the consumer discarded. Counts crashes between a delivery and its"
                        + " acknowledgement.",
                stats.effectsDeduplicated());
        counter(out, "cairn_dispatch_failures_total",
                "Deliveries the consumer refused.", stats.dispatchFailures());

        out.append("# HELP cairn_commands_rejected_total Commands refused, by reason.\n");
        out.append("# TYPE cairn_commands_rejected_total counter\n");
        for (RejectionCode code : RejectionCode.values()) {
            out.append("cairn_commands_rejected_total{code=\"")
                    .append(code.name().toLowerCase())
                    .append("\"} ")
                    .append(stats.rejections().getOrDefault(code, 0L))
                    .append('\n');
        }

        gauge(out, "cairn_log_applied_index", "Highest log index applied.", state.appliedIndex());
        gauge(out, "cairn_log_first_index", "Lowest log index still stored.", stats.logFirstIndex());
        gauge(out, "cairn_log_last_index", "Highest log index stored.", stats.logLastIndex());
        gauge(out, "cairn_log_bytes", "Size of the command log on disk.", stats.logBytes());
        gauge(out, "cairn_snapshot_index",
                "Log index of the newest snapshot.", stats.snapshotIndex());

        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder out, String name, String help, long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" counter\n");
        out.append(name).append(' ').append(value).append('\n');
    }
}
