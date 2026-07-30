-- 添加里程碑开始日期和截止日期字段
-- target_date 保留用于向后兼容，start_date 和 end_date 是新语义
ALTER TABLE milestone ADD COLUMN start_date date;
ALTER TABLE milestone ADD COLUMN end_date date;

-- 添加日期约束：start_date 必须在 end_date 之前
ALTER TABLE milestone ADD CONSTRAINT chk_milestone_dates
    CHECK (start_date IS NULL OR end_date IS NULL OR start_date <= end_date);

-- 历史数据回填：将 target_date 复制到 end_date
UPDATE milestone SET end_date = target_date WHERE target_date IS NOT NULL AND end_date IS NULL;
