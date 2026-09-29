package com.smartcloset.workflow.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartcloset.image.ProcessingException;
import com.smartcloset.workflow.Workflow.*;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Four business tables. No file or provider I/O is performed in these transactions. */
@Repository
public class WorkflowRepository {
    private final JobMapper jobs;
    private final StepMapper steps;
    private final AttemptMapper attempts;
    private final AssetMapper assets;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public WorkflowRepository(JobMapper jobs, StepMapper steps, AttemptMapper attempts, AssetMapper assets,
            ObjectMapper json, PlatformTransactionManager manager, Flyway workflowMigrations) {
        this.jobs = jobs; this.steps = steps; this.attempts = attempts; this.assets = assets;
        this.json = json; this.tx = new TransactionTemplate(manager);
    }

    public synchronized boolean exists(UUID id) { return jobs.selectById(id.toString()) != null; }

    public synchronized void insert(Job job, List<AssetRow> inputAssets) {
        tx.executeWithoutResult(t -> {
            JobRow row = row(job); row.version = 0L;
            jobs.insert(row);
            for (var result : job.steps()) steps.insert(step(job.id(), result));
            inputAssets.forEach(assets::insert);
        });
    }

    public synchronized void save(Job job, AssetRow asset) {
        tx.executeWithoutResult(t -> {
            var before = requireRow(job.id());
            var next = row(job);
            next.selectedAssetId = before.selectedAssetId;
            next.parentJobId = before.parentJobId;
            next.version = before.version + 1;
            if (jobs.update(next, new UpdateWrapper<JobRow>().eq("id", before.id).eq("version", before.version)) != 1)
                throw new IllegalStateException("Concurrent workflow update");
            for (var result : job.steps()) steps.updateById(step(job.id(), result));
            if (asset != null && assetByFile(job.id(), asset.fileName) == null) assets.insert(asset);
        });
    }

    public synchronized Job get(UUID id) {
        var row = requireRow(id);
        var results = steps.selectList(new QueryWrapper<StepRow>().eq("job_id", row.id).orderByAsc("step_no"))
                .stream().map(s -> json.readValue(s.resultJson, StepResult.class)).toList();
        return new Job(id, row.fingerprint, Status.valueOf(row.status), instant(row.createdAtMs), instant(row.updatedAtMs),
                json.readValue(row.requestJson, Request.class), row.inputUrl, row.outputUrl, row.requiresReview == 1,
                results, row.errorJson == null ? null : json.readValue(row.errorJson, Failure.class),
                uuid(row.parentJobId), uuid(row.selectedAssetId),
                assets.selectList(new QueryWrapper<AssetRow>().eq("job_id", row.id).orderByAsc("file_name")).stream().map(this::assetView).toList(),
                attempts.selectList(new QueryWrapper<AttemptRow>().eq("job_id", row.id).orderByAsc("step_no", "attempt_no"))
                        .stream().map(this::attemptView).toList());
    }

    public synchronized History history(int page, int size, Status status) {
        if (page < 1 || size < 1 || size > 50) throw ProcessingException.invalid("page 从 1 开始，size 为 1 到 50。");
        var q = new QueryWrapper<JobRow>().eq(status != null, "status", status == null ? null : status.name())
                .orderByDesc("created_at_ms", "id");
        var result = jobs.selectPage(new Page<JobRow>(page, size), q);
        var items = result.getRecords().stream().map(r -> new Summary(UUID.fromString(r.id), Status.valueOf(r.status),
                instant(r.createdAtMs), instant(r.updatedAtMs), r.outputUrl,
                steps.selectCount(new QueryWrapper<StepRow>().eq("job_id", r.id).eq("status", "SUCCEEDED")).intValue(),
                steps.selectCount(new QueryWrapper<StepRow>().eq("job_id", r.id)).intValue(), r.requiresReview == 1,
                uuid(r.selectedAssetId), uuid(r.parentJobId))).toList();
        return new History(items, result.getTotal(), page, size);
    }

    public synchronized long queuedCount() { return jobs.selectCount(new QueryWrapper<JobRow>().eq("status", "QUEUED")); }

    public synchronized UUID claimNext() {
        var result = jobs.selectPage(new Page<JobRow>(1, 1, false),
                new QueryWrapper<JobRow>().eq("status", "QUEUED").orderByAsc("created_at_ms", "id"));
        if (result.getRecords().isEmpty()) return null;
        var row = result.getRecords().getFirst();
        int changed = jobs.update(null, new UpdateWrapper<JobRow>().eq("id", row.id).eq("status", "QUEUED")
                .eq("version", row.version).set("status", "RUNNING").set("updated_at_ms", System.currentTimeMillis())
                .set("version", row.version + 1));
        return changed == 1 ? UUID.fromString(row.id) : null;
    }

    public synchronized List<UUID> unfinished() {
        return jobs.selectList(new QueryWrapper<JobRow>().in("status", "RUNNING", "CANCEL_REQUESTED"))
                .stream().map(r -> UUID.fromString(r.id)).toList();
    }

    public synchronized String startAttempt(UUID jobId, int stepNo, com.smartcloset.generation.GenerationService.Prepared prepared) {
        var row = new AttemptRow(); row.id = UUID.randomUUID().toString(); row.jobId = jobId.toString();
        row.stepNo = stepNo; row.attemptNo = 1; row.providerId = prepared.provider(); row.modelId = prepared.model();
        row.status = "DISPATCHING"; row.requestJson = json.writeValueAsString(prepared); row.startedAtMs = System.currentTimeMillis();
        attempts.insert(row);
        return row.id;
    }

    public synchronized void finishAttempt(String id, String status, String providerRequestId,
            tools.jackson.databind.JsonNode usage, String errorCode) {
        var row = attempts.selectById(id);
        row.status = status; row.providerRequestId = providerRequestId;
        row.usageJson = usage == null ? null : json.writeValueAsString(usage);
        row.errorCode = errorCode; row.finishedAtMs = System.currentTimeMillis(); attempts.updateById(row);
    }

    public synchronized void recoverAttempts(UUID id) {
        for (var row : attempts.selectList(new QueryWrapper<AttemptRow>().eq("job_id", id.toString()).eq("status", "DISPATCHING")))
            finishAttempt(row.id, "UNKNOWN", row.providerRequestId, null, "SERVER_RESTARTED");
    }

    public synchronized AssetRow asset(UUID id) {
        var row = assets.selectById(id.toString());
        if (row == null) throw new ProcessingException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "没有找到该图片资产。");
        return row;
    }

    public synchronized AssetRow assetByFile(UUID id, String fileName) {
        return assets.selectOne(new QueryWrapper<AssetRow>().eq("job_id", id.toString()).eq("file_name", fileName));
    }

    public synchronized Job review(UUID jobId, UUID assetId, ReviewRequest request) {
        if (request == null || !Set.of("APPROVED", "REJECTED", "PENDING").contains(Objects.toString(request.decision(), ""))
                || (request.note() != null && request.note().length() > 1000))
            throw ProcessingException.invalid("decision 为 APPROVED、REJECTED 或 PENDING；备注最多 1000 字。");
        tx.executeWithoutResult(t -> {
            var job = requireRow(jobId); var asset = asset(assetId);
            if (!asset.jobId.equals(job.id) || "INPUT".equals(asset.kind)) throw ProcessingException.invalid("请选择此任务的一张输出图片。");
            asset.reviewStatus = request.decision(); asset.reviewNote = request.note(); asset.reviewedAtMs = System.currentTimeMillis();
            assets.updateById(asset);
            if ("APPROVED".equals(request.decision())) job.selectedAssetId = asset.id;
            else if (asset.id.equals(job.selectedAssetId)) job.selectedAssetId = null;
            job.version++; job.updatedAtMs = System.currentTimeMillis(); jobs.updateById(job);
        });
        return get(jobId);
    }

    private JobRow requireRow(UUID id) {
        var row = jobs.selectById(id.toString());
        if (row == null) throw new ProcessingException(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", "没有找到该工作流。");
        return row;
    }
    private JobRow row(Job job) {
        var r = new JobRow(); r.id = job.id().toString(); r.fingerprint = job.fingerprint(); r.status = job.status().name();
        r.createdAtMs = job.createdAt().toEpochMilli(); r.updatedAtMs = job.updatedAt().toEpochMilli();
        r.requestJson = json.writeValueAsString(job.request()); r.inputUrl = job.inputUrl(); r.outputUrl = job.outputUrl();
        r.requiresReview = job.requiresReview() ? 1 : 0; r.errorJson = job.error() == null ? null : json.writeValueAsString(job.error());
        r.parentJobId = job.parentJobId() == null ? null : job.parentJobId().toString();
        r.selectedAssetId = job.selectedAssetId() == null ? null : job.selectedAssetId().toString();
        return r;
    }
    private StepRow step(UUID id, StepResult result) {
        var r = new StepRow(); r.id = id + ":" + result.index(); r.jobId = id.toString(); r.stepNo = result.index();
        r.stepType = result.type().name(); r.status = result.status().name(); r.resultJson = json.writeValueAsString(result); return r;
    }
    private Asset assetView(AssetRow r) {
        return new Asset(UUID.fromString(r.id), "/api/workflows/" + r.jobId + "/files/" + r.fileName, r.kind,
                r.sha256, r.byteSize, r.width, r.height, r.reviewStatus, r.reviewNote, instant(r.reviewedAtMs));
    }
    private Attempt attemptView(AttemptRow r) {
        return new Attempt(UUID.fromString(r.id), r.stepNo, r.providerId, r.modelId, r.providerRequestId, r.status,
                instant(r.startedAtMs), instant(r.finishedAtMs), r.usageJson == null ? null : json.readTree(r.usageJson), r.errorCode);
    }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static Instant instant(Long value) { return value == null ? null : Instant.ofEpochMilli(value); }
}
