package cn.zhixu.controller;

import cn.zhixu.service.SchoolService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  final SchoolService school;
  final ConcurrentHashMap<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

  ApiController(SchoolService school) {
    this.school = school;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> user(HttpServletRequest r) {
    return (Map<String, Object>) r.getAttribute("user");
  }

  Object wire(Object data) {
    if (data instanceof LocalDateTime t) return t.atOffset(ZoneOffset.ofHours(8));
    if (data instanceof java.sql.Timestamp t)
      return t.toLocalDateTime().atOffset(ZoneOffset.ofHours(8));
    if (data instanceof java.sql.Date d) return d.toLocalDate();
    if (data instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(String.valueOf(k), wire(v)));
      return out;
    }
    if (data instanceof List<?> l) return l.stream().map(this::wire).toList();
    return data;
  }

  Map<String, Object> ok(Object data) {
    return Map.of(
        "code",
        0,
        "message",
        "OK",
        "data",
        data == null ? Map.of() : wire(data),
        "requestId",
        UUID.randomUUID().toString());
  }

  void update(long sid) {
    for (SseEmitter e : subscribers.getOrDefault(sid, Set.of()))
      try {
        e.send(SseEmitter.event().name("refresh").data(sid));
      } catch (Exception ex) {
        subscribers.get(sid).remove(e);
      }
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    school.database().queryForObject("SELECT 1", Integer.class);
    return ok(
        Map.of(
            "database",
            "MySQL",
            "serverTime",
            school.now().atOffset(ZoneOffset.ofHours(8)).toString()));
  }

  @PostMapping("/auth/login")
  Map<String, Object> login(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return ok(school.login(b, r.getRemoteAddr()));
  }

  @PostMapping("/auth/register")
  Map<String, Object> register(@RequestBody Map<String, Object> b) {
    school.register(b);
    return ok(null);
  }

  @PostMapping("/auth/reset-request")
  Map<String, Object> reset(@RequestBody Map<String, Object> b) {
    String account = SchoolService.text(b, "account");
    SchoolService.require(account.matches("[A-Za-z0-9]{6,20}"), 400, "账号格式无效");
    school
        .database()
        .update(
            "INSERT INTO reset_requests(account,created_at) VALUES(?,?)", account, school.now());
    return ok(Map.of("message", "已登记，请携带学号联系学校管理员核验重置。当前未接通短信或邮件。"));
  }

  @GetMapping("/me")
  Map<String, Object> me(HttpServletRequest r) {
    return ok(user(r));
  }

  @PostMapping("/logout")
  Map<String, Object> logout(HttpServletRequest r) {
    school
        .database()
        .update(
            "DELETE FROM tokens WHERE token_hash=?",
            SchoolService.hash(r.getHeader("Authorization").substring(7)));
    return ok(null);
  }

  @GetMapping("/timetable")
  Map<String, Object> timetable(@RequestParam String weekStart, HttpServletRequest r) {
    LocalDate start = LocalDate.parse(weekStart);
    return ok(school.timetable(user(r), start, start.plusDays(6)));
  }

  @GetMapping("/lesson-sessions/current")
  Map<String, Object> current(HttpServletRequest r) {
    List<Map<String, Object>> lessons =
        school.timetable(
            user(r), LocalDate.now(school.serverClock()), LocalDate.now(school.serverClock()));
    return ok(
        lessons.stream()
            .filter(
                l ->
                    !school.now().isBefore(SchoolService.time(l.get("start_at")))
                        && school.now().isBefore(SchoolService.time(l.get("end_at"))))
            .toList());
  }

  /** 学生签到成功后，通过 SSE 通知同课次的教师和学生刷新座位图。 */
  @PostMapping("/lesson-sessions/{sid}/check-in")
  Map<String, Object> check(@PathVariable long sid, HttpServletRequest r) {
    Object attendance = school.checkIn(user(r), sid);
    update(sid);
    return ok(attendance);
  }

  @PatchMapping("/lesson-sessions/{sid}/time")
  Map<String, Object> time(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    Object d = school.reschedule(user(r), sid, b);
    update(sid);
    return ok(d);
  }

  /** 返回座位图；服务层会根据角色脱敏其他学生的敏感字段。 */
  @GetMapping("/lesson-sessions/{sid}/seats")
  Map<String, Object> seats(@PathVariable long sid, HttpServletRequest r) {
    return ok(school.seats(user(r), sid));
  }

  /** 教师更正考勤后，立即向当前课堂推送刷新事件。 */
  @PatchMapping("/lesson-sessions/{sid}/attendance/{student}")
  Map<String, Object> attendance(
      @PathVariable long sid,
      @PathVariable long student,
      @RequestBody Map<String, Object> b,
      HttpServletRequest r) {
    school.changeAttendance(user(r), sid, student, b);
    update(sid);
    return ok(null);
  }

  @PostMapping("/lesson-sessions/{sid}/hand-raises")
  Map<String, Object> hand(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    SchoolService.require(b.get("raised") instanceof Boolean, 400, "举手状态须为布尔值");
    school.hand(user(r), sid, (Boolean) b.get("raised"));
    update(sid);
    return ok(null);
  }

  @GetMapping("/lesson-sessions/{sid}/messages")
  Map<String, Object> messages(@PathVariable long sid, HttpServletRequest r) {
    return ok(school.messages(user(r), sid));
  }

  @PostMapping("/lesson-sessions/{sid}/messages")
  Map<String, Object> message(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    school.message(user(r), sid, b);
    update(sid);
    return ok(null);
  }

  @PostMapping("/lesson-sessions/{sid}/scores")
  Map<String, Object> score(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    school.score(user(r), sid, b);
    update(sid);
    return ok(null);
  }

  @GetMapping(value = "/lesson-sessions/{sid}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter events(@PathVariable long sid, HttpServletRequest r) throws IOException {
    SchoolService.role(user(r), "student", "teacher");
    school.lesson(user(r), sid);
    SseEmitter e = new SseEmitter(60000L);
    Set<SseEmitter> group = subscribers.computeIfAbsent(sid, k -> ConcurrentHashMap.newKeySet());
    group.add(e);
    e.onCompletion(() -> group.remove(e));
    e.onTimeout(
        () -> {
          group.remove(e);
          e.complete();
        });
    e.onError(t -> group.remove(e));
    e.send(SseEmitter.event().name("ready").data(sid));
    return e;
  }

  @GetMapping("/leave/eligible-sessions")
  Map<String, Object> eligible(
      @RequestParam String startDate, @RequestParam String endDate, HttpServletRequest r) {
    SchoolService.role(user(r), "student");
    return ok(school.timetable(user(r), LocalDate.parse(startDate), LocalDate.parse(endDate)));
  }

  @PostMapping("/leave/applications")
  Map<String, Object> apply(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return ok(school.apply(user(r), b));
  }

  @GetMapping("/leave/applications/mine")
  Map<String, Object> mine(HttpServletRequest r) {
    return ok(school.applications(user(r), false));
  }

  @GetMapping("/approvals/pending")
  Map<String, Object> pending(HttpServletRequest r) {
    SchoolService.role(user(r), "teacher", "vice", "dean");
    return ok(school.applications(user(r), true));
  }

  @PostMapping("/approvals/{aid}/decision")
  Map<String, Object> decide(
      @PathVariable long aid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    school.decide(user(r), aid, b);
    for (Map<String, Object> t :
        school
            .database()
            .queryForList("SELECT session_id FROM leave_targets WHERE application_id=?", aid))
      update(SchoolService.num(t.get("session_id")));
    return ok(null);
  }

  @GetMapping("/reports/attendance")
  Map<String, Object> report(
      @RequestParam String startDate,
      @RequestParam String endDate,
      @RequestParam(defaultValue = "") String student,
      HttpServletRequest r) {
    return ok(
        school.report(user(r), LocalDate.parse(startDate), LocalDate.parse(endDate), student));
  }

  @GetMapping("/notifications")
  Map<String, Object> notifications(HttpServletRequest r) {
    return ok(
        school
            .database()
            .queryForList(
                "SELECT id,body,created_at FROM notifications WHERE user_id=? ORDER BY id DESC"
                    + " LIMIT 100",
                SchoolService.id(user(r))));
  }

  @PostMapping("/attachments")
  @Transactional
  Map<String, Object> upload(@RequestParam MultipartFile file, HttpServletRequest r)
      throws IOException {
    SchoolService.role(user(r), "student");
    byte[] bytes = file.getBytes();
    SchoolService.require(
        bytes.length > 0 && bytes.length <= 5 * 1024 * 1024, 400, "图片需为 1 字节–5MB");
    boolean png =
        bytes.length >= 8
            && bytes[0] == (byte) 0x89
            && bytes[1] == 0x50
            && bytes[2] == 0x4e
            && bytes[3] == 0x47;
    boolean jpg =
        bytes.length >= 3
            && bytes[0] == (byte) 0xff
            && bytes[1] == (byte) 0xd8
            && bytes[2] == (byte) 0xff;
    SchoolService.require(
        (png || jpg) && ImageIO.read(new ByteArrayInputStream(bytes)) != null,
        400,
        "只接受有效 PNG/JPEG 图片");
    String name =
        Objects.toString(file.getOriginalFilename(), "材料图片").replaceAll("[\\\\/\\r\\n]", "_");
    if (name.length() > 150) name = name.substring(name.length() - 150);
    school
        .database()
        .update(
            "INSERT INTO attachments(owner_id,name,media_type,content) VALUES(?,?,?,?)",
            SchoolService.id(user(r)),
            name,
            png ? "image/png" : "image/jpeg",
            bytes);
    return ok(Map.of("id", school.inserted(), "name", name));
  }

  @GetMapping("/attachments/{aid}")
  ResponseEntity<byte[]> image(@PathVariable long aid, HttpServletRequest r) {
    Map<String, Object> u = user(r);
    Map<String, Object> f = school.one("SELECT * FROM attachments WHERE id=?", aid);
    boolean allowed = SchoolService.id(u) == SchoolService.num(f.get("owner_id"));
    if (!allowed)
      for (Map<String, Object> a :
          school
              .database()
              .queryForList(
                  "SELECT a.*,u.dept_id FROM leave_applications a JOIN users u ON u.id=a.student_id"
                      + " WHERE a.attachment_id=?",
                  aid))
        if (school.visible(u, a)) {
          allowed = true;
          break;
        }
    SchoolService.require(allowed, 403, "无权读取此凭证图片");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) f.get("media_type")))
        .header("Cache-Control", "no-store")
        .body((byte[]) f.get("content"));
  }
}
