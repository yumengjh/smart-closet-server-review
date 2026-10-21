-- Who the recommendation is for (design 2026-10-20 推荐对象). All optional; NULL keeps the behaviour before.
-- age_band stores a band only (never a birth date); age_feel is the wanted feel and wins over the band.
ALTER TABLE recommend_setting ADD COLUMN age_band VARCHAR(16);
ALTER TABLE recommend_setting ADD COLUMN age_feel VARCHAR(16);
ALTER TABLE recommend_setting ADD COLUMN dress_expression VARCHAR(16);
