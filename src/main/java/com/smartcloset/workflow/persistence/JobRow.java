package com.smartcloset.workflow.persistence;

import com.baomidou.mybatisplus.annotation.*;

@TableName("workflow_job")
public class JobRow {
    @TableId(type = IdType.INPUT)
    public String id;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String fingerprint;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long createdAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long updatedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String requestJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String inputUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String outputUrl;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Integer requiresReview;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String errorJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String parentJobId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String selectedAssetId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long version;
    @TableField("owner_username")
    public String ownerUsername;
}
