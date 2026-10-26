## 一、最小数据库约束

1. **唯一约束**  
   - `course_session`：`UNIQUE(course_id, session_date, start_time)`，保证课次唯一。  
   - `attendance`：`UNIQUE(session_id, student_id)`，每生每课次仅一条签到记录。  
   - `approval_flow`：`UNIQUE(request_id, step_no)`，避免同一审批节点重复。  
   - `teacher_course`：`UNIQUE(teacher_id, course_id)`，防重复任课关系。

2. **外键与状态约束**  
   - `course.teacher_id`、`session.course_id`、`attendance.session_id`、`attendance.student_id` 均设外键。  
   - `attendance.status ENUM('PRESENT','LATE','ABSENT')`。  
   - `approval_flow.role ENUM('teacher','counselor','vice','dean')`，`status ENUM('PENDING','APPROVED','REJECTED')`。  
   - `session_date` 与 `start_time` 为 `DATETIME`，服务端以数据库时间为准，避免客户端篡改。

3. **检查约束**  
   - 签到时间 `checkin_time >= session_start`，且服务端计算状态：  
     `TIMESTAMPDIFF(MINUTE, session_start, checkin_time) <=5` → PRESENT；`(5,30]` → LATE；`>30` → ABSENT 且禁止补签。  
   - 审批跨度字段 `date_span_days INT` 由服务端计算，不允许前端传入。

4. **索引**  
   - `attendance(session_id, student_id)`、`approval_flow(request_id, step_no)`、`session(course_id, session_date)`。

## 二、跨课程多教师首审方案

任课教师首审按“课程-教师”关系路由，而非按学生或班级。  
- 每门课程可有多位任课教师，首审请求进入 `approval_flow` 时，按 `course_id` 找 `teacher_course` 中所有教师。  
- 采用“首审抢占式”或“指定首审教师”两种模式：  
  - 抢占式：任一任课教师可首审，`UPDATE approval_flow SET approver_id=?, status='APPROVED' WHERE request_id=? AND step_no=1 AND status='PENDING'`，影响行数 1 才成功。  
  - 指定式：请求表增加 `first_teacher_id`，仅该教师可审。  
- 首审通过后，按日期跨度进入终审：  
  - `date_span_days <=2`：教师终审。  
  - `>2` 且非学期：教师 → 分院长。  
  - 学期：教师 → 分院长 → 院长。  
- 多教师场景下，终审角色仍按流程表 `role` 与 `step_no` 控制，不因首审教师不同而改变。

## 三、防越权检查

1. **角色-节点绑定**  
   - `approval_flow` 每步存 `required_role`，审批时校验当前用户角色是否匹配。  
   - 教师只能审自己任课课程；分院长只能审本院；院长只能审全校。  
   - 查询条件必须带 `user_id` 与 `role` 双重过滤。

2. **数据范围隔离**  
   - 学生只能查自己 `attendance`；教师只能查自己 `course` 下 `session`；辅导员按所带班级；分院长按学院；院长全校。  
   - 所有列表 SQL 必须拼接数据权限条件，禁止仅前端隐藏。

3. **状态机防跳步**  
   - 审批仅允许 `PENDING → APPROVED/REJECTED`，且 `step_no` 必须连续。  
   - 终审未完成前，不允许修改签到状态或删除课次。

4. **服务端时间与补签**  
   - `checkin_time` 由服务端生成，禁止客户端传入。  
   - `>30min` 时直接写 ABSENT，并置 `allow_makeup=0`，后续补签接口校验该标志。

## 四、关键事务测试

1. **签到边界**  
   - 4分59秒 → PRESENT；5分00秒 → LATE；30分00秒 → LATE；30分01秒 → ABSENT 且补签失败。

2. **并发首审**  
   - 两位任课教师同时首审同一请求，仅一人成功，另一人收到“已处理”。

3. **审批路由**  
   - 跨度1天：教师终审后直接完成。  
   - 跨度3天：教师首审后进入分院长，分院长未审时院长不可审。  
   - 学期：必须依次教师、分院长、院长，跳步返回错误。

4. **越权审批**  
   - 非任课教师审首审 → 拒绝。  
   - 分院长审其他学院 → 拒绝。  
   - 学生查他人签到 → 空结果或拒绝。

5. **重复签到与补签**  
   - 同一 `session_id + student_id` 第二次插入触发唯一约束。  
   - ABSENT 后调用补签接口，因 `allow_makeup=0` 失败。

6. **事务回滚**  
   - 审批通过时同时更新 `approval_flow` 与 `request` 状态，任一步失败整体回滚，避免半完成状态。