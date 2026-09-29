package com.smartcloset.workflow.persistence;

import com.baomidou.mybatisplus.annotation.*;

@TableName("workflow_step")
public class StepRow {
    @TableId(type = IdType.INPUT)
    public String id;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String jobId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public Integer stepNo;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String stepType;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String status;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    public String resultJson;
}
