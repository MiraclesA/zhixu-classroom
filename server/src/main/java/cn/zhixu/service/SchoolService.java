package cn.zhixu.service;

import cn.zhixu.common.ApiError;
import cn.zhixu.model.AttendanceStatus;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SchoolService {
  final JdbcTemplate db;
  final Clock clock;
  final String invite;
  final TaskScheduler scheduler;
  final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  final ConcurrentHashMap<String, List<Long>> attempts = new ConcurrentHashMap<>();
  final Object absenceScheduleLock = new Object();
  ScheduledFuture<?> nextAbsenceCheck;

  public SchoolService(
      JdbcTemplate db,
      Clock clock,
      TaskScheduler scheduler,
      @Value("${zhixu.teacher-invite}") String invite) {
    this.db = db;
    this.clock = clock;
    this.scheduler = scheduler;
    this.invite = invite;
  }

  public JdbcTemplate database() {
    return db;
  }

  public Clock serverClock() {
    return clock;
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static long id(Map<String, Object> u) {
    return num(u.get("id"));
  }

  public static long num(Object x) {
    if (x instanceof Number n) {
      require(Double.isFinite(n.doubleValue()) && n.doubleValue() == n.longValue(), 400, "数值必须为整数");
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(x));
    } catch (Exception e) {
      throw new ApiError(400, "编号格式错误");
    }
  }

  public static String text(Map<String, Object> x, String k) {
    return Objects.toString(x.get(k), "").trim();
  }

  public static void require(boolean condition, int status, String message) {
    if (!condition) throw new ApiError(status, message);
  }

  public static void role(Map<String, Object> u, String... roles) {
    require(Arrays.asList(roles).contains(u.get("role")), 403, "当前身份无权执行该操作");
  }

  public LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  /**
   * 在某一课次“开课后 30 分钟”的检查点补写缺勤记录；与学生点击“立即报到”的 {@link #checkIn(Map, long)} 完全独立。
   *
   * <p>此方法不是每分钟轮询：{@link #scheduleNextAbsenceCheck()} 只为下一节待检查课次注册一次性任务。任务触发后，才执行本 SQL，
   * 然后再安排下一次。应用刚启动或教师将上课时间改到已过 30 分钟时，会额外执行一次补偿，防止服务关闭期间遗漏。
   *
   * <p>SQL 只为没有 attendance 记录的学生插入 {@code ABSENT}。联合主键 {@code (session_id, student_id)} 与 {@code ON
   * DUPLICATE KEY} 使操作可重复执行；已签到、已请假或被教师更正过的记录绝不会被覆盖。
   */
  public void finalizeAbsences() {
    LocalDateTime checkedAt = now();
    LocalDateTime absenceDeadline = checkedAt.minusMinutes(30);
    db.update(
        """
        INSERT INTO attendance(session_id, student_id, status)
        SELECT l.id, u.id, 'ABSENT'
        FROM lessons l
        JOIN courses c ON c.id=l.course_id
        JOIN users u ON u.class_id=c.class_id AND u.role='student'
        WHERE l.start_at<=?
          AND l.end_at>?
        ON DUPLICATE KEY UPDATE session_id=attendance.session_id
        """,
        absenceDeadline,
        checkedAt);
  }

  /** 服务可用后只补一次遗漏记录，再把下一次检查精确预约到下一节课的开始后 30 分钟。 */
  @EventListener(ApplicationReadyEvent.class)
  public void initializeAbsenceSchedule() {
    finalizeAbsences();
    scheduleNextAbsenceCheck();
  }

  /**
   * 查找下一节尚未到达检查点的课，并只保留一个一次性调度任务。
   *
   * <p>例如课次 10:00 开始，则任务在 10:30 执行一次；它不是每分钟查询 lessons 表。若没有未来检查点，不会保留空转任务。
   */
  void scheduleNextAbsenceCheck() {
    LocalDateTime checkedAt = now();
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT MIN(start_at) AS next_start FROM lessons WHERE start_at>? AND end_at>?",
            checkedAt.minusMinutes(30),
            checkedAt);
    Object nextStartValue = rows.isEmpty() ? null : rows.get(0).get("next_start");
    LocalDateTime nextStart = nextStartValue == null ? null : time(nextStartValue);

    synchronized (absenceScheduleLock) {
      if (nextAbsenceCheck != null) {
        nextAbsenceCheck.cancel(false);
        nextAbsenceCheck = null;
      }
      if (nextStart == null) return;

      Instant deadline = nextStart.plusMinutes(30).atZone(clock.getZone()).toInstant();
      nextAbsenceCheck = scheduler.schedule(this::runScheduledAbsenceCheck, deadline);
    }
  }

  /** 一次性任务的入口：处理当前到点课次，并立即预约下一节课。 */
  void runScheduledAbsenceCheck() {
    try {
      finalizeAbsences();
    } finally {
      scheduleNextAbsenceCheck();
    }
  }

  /**
   * 教师改课时间后，必须等事务提交再取消旧定时器、按新时间重新预约；提交失败时不改变已有预约。
   */
  void rescheduleAbsenceCheckAfterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      finalizeAbsences();
      scheduleNextAbsenceCheck();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            finalizeAbsences();
            scheduleNextAbsenceCheck();
          }
        });
  }

  public static LocalDateTime time(Object o) {
    return o instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) o;
  }

  public Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = db.queryForList(sql, args);
    require(!rows.isEmpty(), 404, "记录不存在");
    return rows.get(0);
  }

  public long inserted() {
    return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
  }

  void audit(
      Map<String, Object> u,
      String action,
      long target,
      String before,
      String after,
      String reason) {
    db.update(
        "INSERT INTO"
            + " audit_logs(actor_id,action,target_id,before_value,after_value,reason,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        id(u),
        action,
        target,
        before,
        after,
        reason,
        now());
  }

  void notify(long user, String message) {
    db.update(
        "INSERT INTO notifications(user_id,body,created_at) VALUES(?,?,?)", user, message, now());
  }

  public Map<String, Object> login(Map<String, Object> body, String remote) {
    String account = text(body, "account"), password = text(body, "password");
    require(account.length() <= 32 && password.length() <= 72, 400, "账号或密码长度无效");
    String key = remote;
    long ms = clock.millis();
    List<Long> list =
        attempts.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
    synchronized (list) {
      list.removeIf(t -> ms - t > 60000);
      require(list.size() < 15, 429, "登录尝试过多，请一分钟后再试");
      list.add(ms);
    }
    List<Map<String, Object>> users =
        db.queryForList(
            "SELECT id,account,name,password_hash,role,dept_id,class_id,seat_no FROM users WHERE"
                + " account=? AND enabled=TRUE",
            account);
    require(
        !users.isEmpty()
            && encoder.matches(password, Objects.toString(users.get(0).get("password_hash"))),
        401,
        "账号或密码不正确");
    Map<String, Object> u = users.get(0);
    require(
        text(body, "role").isEmpty() || u.get("role").equals(body.get("role")), 403, "该账号与所选身份不一致");
    u.remove("password_hash");
    synchronized (list) {
      list.remove(Long.valueOf(ms));
    }
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = HexFormat.of().formatHex(bytes);
    db.update("INSERT INTO tokens VALUES(?,?,?)", hash(token), id(u), now().plusHours(12));
    return Map.of("token", token, "user", u);
  }

  @Transactional
  public void register(Map<String, Object> body) {
    String account = text(body, "account"),
        name = text(body, "name"),
        password = text(body, "password"),
        role = text(body, "role");
    require(
        account.matches("[A-Za-z0-9]{6,20}")
            && name.length() > 0
            && name.length() <= 50
            && password.length() >= 8
            && password.length() <= 32
            && password.getBytes(StandardCharsets.UTF_8).length <= 72,
        400,
        "姓名、账号或密码格式无效（密码 8–32 位且不超过 72 字节）");
    require(role.equals("student") || role.equals("teacher"), 403, "该身份不开放注册");
    if (role.equals("student")) {
      Map<String, Object> roster = one("SELECT * FROM rosters WHERE account=? FOR UPDATE", account);
      require(
          Boolean.FALSE.equals(roster.get("claimed"))
              && !((Number)
                      db.queryForObject(
                          "SELECT COUNT(*) FROM users WHERE account=?", Integer.class, account))
                  .equals(1),
          409,
          "账号已被注册");
      require(roster.get("name").equals(name), 403, "学号与姓名不符合学校预置名单");
      long clazz = num(roster.get("class_id"));
      Map<String, Object> c = one("SELECT * FROM classes WHERE id=?", clazz);
      db.update(
          "INSERT INTO users(account,name,password_hash,role,dept_id,class_id,seat_no)"
              + " VALUES(?,?,?,'student',?,?,?)",
          account,
          name,
          encoder.encode(password),
          c.get("dept_id"),
          clazz,
          db.queryForObject(
              "SELECT COALESCE(MAX(seat_no),-1)+1 FROM users WHERE class_id=?",
              Integer.class,
              clazz));
      db.update("UPDATE rosters SET claimed=TRUE WHERE account=?", account);
    } else {
      require(
          !invite.isBlank()
              && MessageDigest.isEqual(
                  invite.getBytes(StandardCharsets.UTF_8),
                  text(body, "code").getBytes(StandardCharsets.UTF_8)),
          403,
          "教师注册未开放或服务器邀请码无效");
      // School personnel allocation is a separate administrator operation.
      db.update(
          "INSERT INTO users(account,name,password_hash,role,dept_id) VALUES(?,?,?,'teacher',1)",
          account,
          name,
          encoder.encode(password));
    }
  }

  public Map<String, Object> lesson(Map<String, Object> u, long sid) {
    Map<String, Object> l =
        one(
            "SELECT l.*,c.teacher_id,c.class_id,c.name,c.room,cl.dept_id FROM lessons l JOIN"
                + " courses c ON c.id=l.course_id JOIN classes cl ON cl.id=c.class_id WHERE l.id=?",
            sid);
    switch (text(u, "role")) {
      case "student" ->
          require(Objects.equals(u.get("class_id"), l.get("class_id")), 403, "不能进入其他班级课次");
      case "teacher" -> require(num(l.get("teacher_id")) == id(u), 403, "只能管理本人任教课次");
      case "counselor", "vice" ->
          require(Objects.equals(u.get("dept_id"), l.get("dept_id")), 403, "不能访问其他学院课次");
      case "dean" -> {}
      default -> throw new ApiError(403, "未知身份");
    }
    return l;
  }

  String scope(Map<String, Object> u, List<Object> args) {
    switch (text(u, "role")) {
      case "student" -> {
        args.add(u.get("class_id"));
        return "c.class_id=?";
      }
      case "teacher" -> {
        args.add(id(u));
        return "c.teacher_id=?";
      }
      case "counselor", "vice" -> {
        args.add(u.get("dept_id"));
        return "cl.dept_id=?";
      }
      case "dean" -> {
        return "1=1";
      }
      default -> throw new ApiError(403, "未知身份");
    }
  }

  public List<Map<String, Object>> timetable(
      Map<String, Object> u, LocalDate start, LocalDate end) {
    require(
        !end.isBefore(start) && ChronoUnit.DAYS.between(start, end) <= 150, 400, "课次查询范围为 0–150 天");
    List<Object> a = new ArrayList<>(List.of(start, end));
    String s = scope(u, a);
    return db.queryForList(
        "SELECT l.id,l.lesson_date,l.period_no,l.start_at,l.end_at,c.id"
            + " course_id,c.name,c.room,c.teacher_id,t.name teacher,c.class_id FROM lessons l JOIN"
            + " courses c ON c.id=l.course_id JOIN classes cl ON cl.id=c.class_id JOIN users t ON"
            + " t.id=c.teacher_id WHERE l.lesson_date BETWEEN ? AND ? AND "
            + s
            + " ORDER BY l.start_at",
        a.toArray());
  }

  @Transactional
  public Map<String, Object> reschedule(Map<String, Object> u, long sid, Map<String, Object> body) {
    role(u, "teacher");
    Map<String, Object> l = lesson(u, sid);
    LocalDateTime start, end;
    try {
      start = LocalDateTime.parse(text(body, "startAt"));
      end = LocalDateTime.parse(text(body, "endAt"));
    } catch (Exception e) {
      throw new ApiError(400, "上课开始和结束时间格式无效");
    }
    require(end.isAfter(start), 400, "结束时间必须晚于开始时间");
    db.queryForList("SELECT id FROM lessons WHERE id=? FOR UPDATE", sid);
    String before = time(l.get("start_at")) + " ~ " + time(l.get("end_at")),
        after = start + " ~ " + end;
    db.update(
        "UPDATE lessons SET lesson_date=?,start_at=?,end_at=? WHERE id=?",
        start.toLocalDate(),
        start,
        end,
        sid);
    audit(u, "LESSON_TIME", sid, before, after, "任课教师调整上课时间");
    rescheduleAbsenceCheckAfterCommit();
    return lesson(u, sid);
  }

  // ==================== 考勤业务 ====================

  /**
   * 按实训需求的时间窗口计算“本次点击签到”的状态： 0–5 分钟为正常，超过 5 分钟且不超过 30 分钟为迟到；超过 30 分钟不允许自助签到。
   *
   * <p>本方法只做点击后的状态判断，不负责自动写入旷课。学生没有点击签到时， 由 {@link #finalizeAbsences()} 定时任务在开课 30 分钟后补写 ABSENT 记录。
   */
  public static String attendanceAt(LocalDateTime start, LocalDateTime checkedAt) {
    require(!checkedAt.isBefore(start), 409, "不是上课时间");
    require(!checkedAt.isAfter(start.plusMinutes(30)), 409, "已超过 30 分钟报到窗口，请联系教师");
    return checkedAt.isAfter(start.plusMinutes(5)) ? "LATE" : "PRESENT";
  }

  /**
   * 学生点击“立即报到”后执行的自助签到入口。
   *
   * <p>先锁定课次，避免并发请求同时创建多条记录；attendance 的联合主键 {@code (session_id, student_id)} 也保证同一学生每节课只有一条考勤记录。
   * 已签到时维持首次结果，重复点击不会改变考勤状态。未点击的学生不会进入本方法， 其超时缺勤由 {@link #finalizeAbsences()} 负责。
   */
  @Transactional
  public Map<String, Object> checkIn(Map<String, Object> u, long sid) {
    role(u, "student");
    Map<String, Object> lesson = lesson(u, sid);
    db.queryForList("SELECT id FROM lessons WHERE id=? FOR UPDATE", sid);

    LocalDateTime checkedAt = now();
    LocalDateTime startAt = time(lesson.get("start_at"));
    LocalDateTime endAt = time(lesson.get("end_at"));
    require(checkedAt.isBefore(endAt), 409, "不是上课时间");

    String status = attendanceAt(startAt, checkedAt);
    db.update(
        """
        INSERT INTO attendance(session_id, student_id, status, checked_at)
        VALUES(?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE session_id=session_id
        """,
        sid,
        id(u),
        status,
        checkedAt);

    return one(
        "SELECT * FROM attendance WHERE session_id=? AND student_id=? FOR UPDATE", sid, id(u));
  }

  /** 读取座位图及当前考勤状态。 无数据库记录并不一定代表缺勤：在 30 分钟报到窗口内显示 UNREPORTED， 窗口结束后显示 ABSENT。学生查看时会移除其他学生的敏感字段。 */
  public List<Map<String, Object>> seats(Map<String, Object> u, long sid) {
    Map<String, Object> lesson = lesson(u, sid);
    boolean checkInWindowClosed = now().isAfter(time(lesson.get("start_at")).plusMinutes(30));
    List<Map<String, Object>> rows =
        db.queryForList(
            """
            SELECT u.id, u.account, u.name, u.seat_no,
                   a.status, a.checked_at, h.raised, s.score, s.remark
            FROM users u
            LEFT JOIN attendance a ON a.student_id=u.id AND a.session_id=?
            LEFT JOIN hands h ON h.student_id=u.id AND h.session_id=?
            LEFT JOIN scores s ON s.student_id=u.id AND s.session_id=?
            WHERE u.class_id=? AND u.role='student'
            ORDER BY u.seat_no
            """,
            sid,
            sid,
            sid,
            lesson.get("class_id"));

    for (Map<String, Object> row : rows) {
      if (row.get("status") == null) {
        row.put("status", checkInWindowClosed ? "ABSENT" : "UNREPORTED");
      }
      if (text(u, "role").equals("student") && num(row.get("id")) != id(u)) {
        row.remove("account");
        row.remove("score");
        row.remove("remark");
        row.remove("checked_at");
      }
    }
    return rows;
  }

  /** 确保教师只能修改自己课次中、本班学生的考勤。 */
  void targetStudent(Map<String, Object> lesson, long studentId) {
    int count =
        db.queryForObject(
            """
            SELECT COUNT(*) FROM users
            WHERE id=? AND class_id=? AND role='student'
            """,
            Integer.class,
            studentId,
            lesson.get("class_id"));
    require(count == 1, 403, "学生不属于该课次");
  }

  /** 教师手动更正考勤。更正原因与修改前后的状态都会写入 audit_logs， 使考勤更改可追溯。 */
  @Transactional
  public void changeAttendance(
      Map<String, Object> u, long sid, long studentId, Map<String, Object> body) {
    role(u, "teacher");
    Map<String, Object> lesson = lesson(u, sid);
    targetStudent(lesson, studentId);

    String status = text(body, "status");
    String reason = text(body, "reason");
    require(
        AttendanceStatus.isPersistable(status) && !reason.isBlank() && reason.length() <= 2000,
        400,
        "状态有效且必须填写更正原因");

    db.queryForList("SELECT id FROM lessons WHERE id=? FOR UPDATE", sid);
    List<Map<String, Object>> oldRows =
        db.queryForList(
            """
            SELECT status FROM attendance
            WHERE session_id=? AND student_id=? FOR UPDATE
            """,
            sid,
            studentId);
    String previous = oldRows.isEmpty() ? "UNREPORTED" : text(oldRows.get(0), "status");

    db.update(
        """
        INSERT INTO attendance(session_id, student_id, status)
        VALUES(?, ?, ?)
        ON DUPLICATE KEY UPDATE status=VALUES(status)
        """,
        sid,
        studentId,
        status);
    audit(u, "ATTENDANCE:" + studentId, sid, previous, status, reason);
  }

  @Transactional
  public void hand(Map<String, Object> u, long sid, boolean raised) {
    role(u, "student");
    Map<String, Object> l = lesson(u, sid);
    require(
        !now().isBefore(time(l.get("start_at"))) && now().isBefore(time(l.get("end_at"))),
        409,
        "不是上课时间");
    db.update(
        "INSERT INTO hands VALUES(?,?,?) ON DUPLICATE KEY UPDATE raised=VALUES(raised)",
        sid,
        id(u),
        raised);
  }

  public List<Map<String, Object>> messages(Map<String, Object> u, long sid) {
    role(u, "student", "teacher");
    lesson(u, sid);
    return db.queryForList(
        "SELECT m.id,m.sender_id,m.receiver_id,m.body,m.created_at,u.name sender,r.name receiver"
            + " FROM messages m JOIN users u ON u.id=m.sender_id LEFT JOIN users r ON"
            + " r.id=m.receiver_id WHERE m.session_id=? AND (m.receiver_id IS NULL OR m.sender_id=?"
            + " OR m.receiver_id=?) ORDER BY m.id DESC LIMIT 200",
        sid,
        id(u),
        id(u));
  }

  @Transactional
  public void message(Map<String, Object> u, long sid, Map<String, Object> body) {
    role(u, "student", "teacher");
    Map<String, Object> l = lesson(u, sid);
    String content = text(body, "body");
    require(!content.isBlank() && content.length() <= 2000, 400, "消息须为 1–2000 字");
    Long receiver = body.get("receiverId") == null ? null : num(body.get("receiverId"));
    if (receiver != null) {
      if (text(u, "role").equals("student"))
        require(receiver == num(l.get("teacher_id")), 403, "学生只能私信任课教师");
      else targetStudent(l, receiver);
    }
    db.update(
        "INSERT INTO messages(session_id,sender_id,receiver_id,body,created_at) VALUES(?,?,?,?,?)",
        sid,
        id(u),
        receiver,
        content,
        now());
  }

  @Transactional
  public void score(Map<String, Object> u, long sid, Map<String, Object> body) {
    role(u, "teacher");
    Map<String, Object> l = lesson(u, sid);
    long student = num(body.get("studentId")), score = num(body.get("score"));
    targetStudent(l, student);
    String remark = text(body, "remark");
    require(score >= 0 && score <= 100 && remark.length() <= 1000, 400, "评分需为 0–100 的整数");
    List<Map<String, Object>> old =
        db.queryForList(
            "SELECT score FROM scores WHERE session_id=? AND student_id=?", sid, student);
    db.update(
        "INSERT INTO scores VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " score=VALUES(score),remark=VALUES(remark)",
        sid,
        student,
        score,
        remark);
    audit(
        u,
        "SCORE:" + student,
        sid,
        old.isEmpty() ? "" : text(old.get(0), "score"),
        "" + score,
        remark);
  }

  @Transactional
  public Map<String, Object> apply(Map<String, Object> u, Map<String, Object> body) {
    role(u, "student");
    String type = text(body, "type"), reason = text(body, "reason");
    require(
        Set.of("short", "two", "long", "term").contains(type)
            && !reason.isBlank()
            && reason.length() <= 2000,
        400,
        "请假类型或原因无效");
    LocalDate start = LocalDate.parse(text(body, "startDate")),
        end = LocalDate.parse(text(body, "endDate"));
    long span = ChronoUnit.DAYS.between(start, end) + 1;
    require(span > 0 && span <= 150, 400, "起止日期必须有效且不超过 150 天");
    if (!type.equals("term")) type = span <= 1 ? "short" : span <= 2 ? "two" : "long";
    require(body.get("sessionIds") instanceof List<?>, 400, "请选择具体课次");
    List<?> raw = (List<?>) body.get("sessionIds");
    Set<Long> selected = new TreeSet<>();
    for (Object v : raw) selected.add(num(v));
    require(!selected.isEmpty() && selected.size() <= 1000, 400, "请选择 1–1000 个具体课次");
    Set<Long> teachers = new HashSet<>();
    for (long sid : selected) {
      Map<String, Object> l = lesson(u, sid);
      LocalDate day = LocalDate.parse(l.get("lesson_date").toString());
      require(!day.isBefore(start) && !day.isAfter(end), 400, "课次不在所选日期区间");
      teachers.add(num(l.get("teacher_id")));
    }
    Long attachment = body.get("attachmentId") == null ? null : num(body.get("attachmentId"));
    if (attachment != null)
      require(
          db.queryForObject(
                  "SELECT COUNT(*) FROM attachments WHERE id=? AND owner_id=?",
                  Integer.class,
                  attachment,
                  id(u))
              == 1,
          403,
          "附件不属于本人");
    require(!type.equals("term") || attachment != null, 400, "学期休学申请必须上传材料图片");
    db.update(
        "INSERT INTO"
            + " leave_applications(student_id,type,start_date,end_date,span_days,reason,attachment_id,status,created_at)"
            + " VALUES(?,?,?,?,?,?,?,'PENDING_TEACHER',?)",
        id(u),
        type,
        start,
        end,
        span,
        reason,
        attachment,
        now());
    long application = inserted();
    for (long sid : selected) db.update("INSERT INTO leave_targets VALUES(?,?)", application, sid);
    for (long teacher : teachers) {
      db.update(
          "INSERT INTO teacher_reviews(application_id,teacher_id) VALUES(?,?)",
          application,
          teacher);
      notify(teacher, "收到请假申请 #" + application + "，等待任课教师首审");
    }
    audit(u, "LEAVE_SUBMIT", application, "", "PENDING_TEACHER", reason);
    return Map.of("id", application);
  }

  public boolean visible(Map<String, Object> u, Map<String, Object> a) {
    String r = text(u, "role");
    if (r.equals("student")) return id(u) == num(a.get("student_id"));
    if (r.equals("teacher"))
      return db.queryForObject(
              "SELECT COUNT(*) FROM teacher_reviews WHERE application_id=? AND teacher_id=?",
              Integer.class,
              a.get("id"),
              id(u))
          == 1;
    if (r.equals("dean")) return true;
    if (r.equals("vice")) return Objects.equals(u.get("dept_id"), a.get("dept_id"));
    return false;
  }

  public List<Map<String, Object>> applications(Map<String, Object> u, boolean pending) {
    role(u, "student", "teacher", "vice", "dean");
    List<Map<String, Object>> rows =
        db.queryForList(
            "SELECT a.*,u.name student,u.dept_id,cl.name class_name FROM leave_applications a JOIN"
                + " users u ON u.id=a.student_id JOIN classes cl ON cl.id=u.class_id ORDER BY a.id"
                + " DESC");
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> a : rows) {
      if (!visible(u, a)) continue;
      if (pending) {
        String expected =
            switch (text(u, "role")) {
              case "teacher" -> "PENDING_TEACHER";
              case "vice" -> "PENDING_VICE_DEAN";
              case "dean" -> "PENDING_DEAN";
              default -> "";
            };
        if (!expected.equals(a.get("status"))) continue;
        if (text(u, "role").equals("teacher")
            && db.queryForObject(
                    "SELECT COUNT(*) FROM teacher_reviews WHERE application_id=? AND teacher_id=?"
                        + " AND decision='PENDING'",
                    Integer.class,
                    a.get("id"),
                    id(u))
                == 0) continue;
      }
      a.put(
          "targets",
          db.queryForList(
              "SELECT l.id,l.lesson_date,l.period_no FROM leave_targets t JOIN lessons l ON"
                  + " l.id=t.session_id WHERE t.application_id=? ORDER BY l.start_at",
              a.get("id")));
      a.put(
          "log",
          db.queryForList(
              "SELECT u.name,u.role,al.after_value decision,al.reason opinion,al.created_at time"
                  + " FROM audit_logs al JOIN users u ON u.id=al.actor_id WHERE al.target_id=? AND"
                  + " al.action='LEAVE_REVIEW' ORDER BY al.id",
              a.get("id")));
      a.put(
          "teacherReviews",
          db.queryForList(
              "SELECT u.name,r.decision FROM teacher_reviews r JOIN users u ON u.id=r.teacher_id"
                  + " WHERE r.application_id=?",
              a.get("id")));
      out.add(a);
    }
    return out;
  }

  @Transactional
  public void decide(Map<String, Object> u, long aid, Map<String, Object> body) {
    role(u, "teacher", "vice", "dean");
    Map<String, Object> a =
        one(
            "SELECT a.*,u.dept_id FROM leave_applications a JOIN users u ON u.id=a.student_id WHERE"
                + " a.id=? FOR UPDATE",
            aid);
    require(visible(u, a), 403, "该申请不在本人审批范围");
    String state = text(a, "status"),
        decision = text(body, "decision"),
        reason = text(body, "reason");
    require(
        Set.of("APPROVED", "REJECTED").contains(decision) && reason.length() <= 2000,
        400,
        "审批结果无效");
    require(!decision.equals("REJECTED") || !reason.isBlank(), 400, "驳回必须填写原因");
    String expected =
        switch (text(u, "role")) {
          case "teacher" -> "PENDING_TEACHER";
          case "vice" -> "PENDING_VICE_DEAN";
          default -> "PENDING_DEAN";
        };
    require(expected.equals(state), 409, "不能重复审批或跳过前置节点");
    String next = decision;
    int stage = ((Number) a.get("stage")).intValue();
    if (text(u, "role").equals("teacher")) {
      require(
          db.update(
                  "UPDATE teacher_reviews SET decision=? WHERE application_id=? AND teacher_id=?"
                      + " AND decision='PENDING'",
                  decision,
                  aid,
                  id(u))
              == 1,
          409,
          "已审批，不能重复提交");
      if (decision.equals("APPROVED")) {
        int remaining =
            db.queryForObject(
                "SELECT COUNT(*) FROM teacher_reviews WHERE application_id=? AND"
                    + " decision='PENDING'",
                Integer.class,
                aid);
        if (remaining > 0) next = "PENDING_TEACHER";
        else if (text(a, "type").equals("term") || num(a.get("span_days")) > 2) {
          next = "PENDING_VICE_DEAN";
          stage = 1;
        } else stage = 1;
      }
    } else if (decision.equals("APPROVED")) {
      if (text(u, "role").equals("vice") && text(a, "type").equals("term")) {
        next = "PENDING_DEAN";
        stage = 2;
      } else stage++;
    }
    db.update("UPDATE leave_applications SET status=?,stage=? WHERE id=?", next, stage, aid);
    audit(u, "LEAVE_REVIEW", aid, state, decision, reason);
    if (next.equals("APPROVED")) {
      List<Map<String, Object>> targets =
          db.queryForList(
              "SELECT session_id FROM leave_targets WHERE application_id=? ORDER BY session_id",
              aid);
      for (Map<String, Object> t : targets) {
        long sid = num(t.get("session_id"));
        db.queryForList("SELECT id FROM lessons WHERE id=? FOR UPDATE", sid);
        List<Map<String, Object>> old =
            db.queryForList(
                "SELECT status FROM attendance WHERE session_id=? AND student_id=?",
                sid,
                a.get("student_id"));
        db.update(
            "INSERT INTO attendance(session_id,student_id,status) VALUES(?,?,'LEAVE') ON DUPLICATE"
                + " KEY UPDATE status='LEAVE'",
            sid,
            a.get("student_id"));
        audit(
            u,
            "LEAVE_ATTENDANCE:" + a.get("student_id"),
            sid,
            old.isEmpty() ? "UNREPORTED" : text(old.get(0), "status"),
            "LEAVE",
            "请假终审 #" + aid);
      }
    }
    notify(num(a.get("student_id")), "申请 #" + aid + "：" + next);
    if (next.equals("PENDING_VICE_DEAN") || next.equals("PENDING_DEAN")) {
      String r = next.equals("PENDING_DEAN") ? "dean" : "vice";
      for (Map<String, Object> staff :
          db.queryForList(
              "SELECT id FROM users WHERE role=? AND (dept_id=? OR role='dean')",
              r,
              a.get("dept_id"))) notify(num(staff.get("id")), "申请 #" + aid + " 等待您的审批");
    }
  }

  public List<Map<String, Object>> report(
      Map<String, Object> u, LocalDate start, LocalDate end, String student) {
    require(
        !end.isBefore(start) && ChronoUnit.DAYS.between(start, end) <= 150, 400, "查询范围须在 150 天以内");
    List<Object> args = new ArrayList<>(List.of(start, end));
    String where = scope(u, args);
    if (text(u, "role").equals("student")) {
      where += " AND u.id=?";
      args.add(id(u));
    }
    if (!student.isBlank()) {
      where += " AND (u.account=? OR u.name LIKE ?)";
      args.add(student);
      args.add("%" + student + "%");
    }
    args.add(0, now().minusMinutes(30));
    return db.queryForList(
        "SELECT u.id student_id,u.name student,u.account,cl.name class_name,cl.major,cl.grade,l.id"
            + " session_id,l.lesson_date,l.period_no,c.name"
            + " course,COALESCE(a.status,IF(l.start_at<?,'ABSENT','UNREPORTED'))"
            + " status,a.checked_at,s.score,s.remark FROM lessons l JOIN courses c ON"
            + " c.id=l.course_id JOIN classes cl ON cl.id=c.class_id JOIN users u ON"
            + " u.class_id=cl.id AND u.role='student' LEFT JOIN attendance a ON a.session_id=l.id"
            + " AND a.student_id=u.id LEFT JOIN scores s ON s.session_id=l.id AND s.student_id=u.id"
            + " WHERE l.lesson_date BETWEEN ? AND ? AND "
            + where
            + " ORDER BY l.start_at,u.seat_no LIMIT 20000",
        args.toArray());
  }
}
