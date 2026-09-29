package com.smartcloset.workflow.persistence;

import com.baomidou.mybatisplus.annotation.*;

@TableName("generation_attempt")
public class AttemptRow {
    @TableId(type = IdType.INPUT)
    public String id;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String jobId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Integer stepNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Integer attemptNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String providerId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String modelId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String providerRequestId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String requestJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long startedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long finishedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String usageJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String errorCode;
}
