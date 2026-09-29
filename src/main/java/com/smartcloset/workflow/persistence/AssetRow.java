package com.smartcloset.workflow.persistence;

import com.baomidou.mybatisplus.annotation.*;

@TableName("image_asset")
public class AssetRow {
    @TableId(type = IdType.INPUT)
    public String id;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String jobId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String fileName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String sha256;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long byteSize;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Integer width;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Integer height;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String kind;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String reviewStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String reviewNote;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long reviewedAtMs;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Long createdAtMs;
}
