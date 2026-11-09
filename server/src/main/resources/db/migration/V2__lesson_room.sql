-- 教室属于具体课次，而不是永远固定在课程上。
-- 这样同一门课程在实训楼、主教学楼等不同地点上课时，可以分别保存真实教室和时间。
ALTER TABLE lessons ADD COLUMN room VARCHAR(100) NULL AFTER course_id;

UPDATE lessons l
JOIN courses c ON c.id = l.course_id
SET l.room = c.room
WHERE l.room IS NULL;

ALTER TABLE lessons MODIFY COLUMN room VARCHAR(100) NOT NULL;
