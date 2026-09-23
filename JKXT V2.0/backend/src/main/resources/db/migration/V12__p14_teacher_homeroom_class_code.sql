-- P14: persist the teacher-file declared 所带班级 (homeroom class) RAW code so the 班主任→班级 binding
-- can be resolved regardless of import order. Previously, importing the teacher file before the class
-- file dropped the binding (teacher.homeroom_class_id left NULL with no record of the intended class),
-- which silently let a 班主任 be scheduled into their own class and the job still reported success.
-- commitTeachers now always stores this raw code; commitClasses backfills homeroom_class_id from it when
-- the matching class arrives. The school_class.homeroom_teacher_id path (班级表班主任工号) is unaffected.
ALTER TABLE teacher ADD COLUMN homeroom_class_code varchar(40);
