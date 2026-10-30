package cn.zhixu.controller;

import cn.zhixu.service.SchoolService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * HTTP / SSE 适配层。
 *
 * <p>控制器负责把请求参数交给 {@link SchoolService}、包装统一响应并在课堂状态变动后发送 SSE 刷新事件； 身份认证由 {@code AuthFilter}
 * 完成，细粒度角色和数据范围校验仍由服务层完成。
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {
  final SchoolService school;

  /** 以课次 ID 分组的 SSE 连接：同一课堂的状态变化只通知该课堂的浏览器。 */
  final ConcurrentHashMap<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

  ApiController(SchoolService school) {
    this.school = school;
  }

  /** 读取 AuthFilter 已认证并放入请求上下文的用户资料。 */
  @SuppressWarnings("unchecked")
  Map<String, Object> user(HttpServletRequest r) {
    return (Map<String, Object>) r.getAttribute("user");
  }

  /** 递归转换 JDBC 时间类型，使 JSON 始终携带 +08:00 时区信息。 */
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

  /** 生成统一成功响应：code、message、data 和可用于排查的 requestId。 */
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

  /** 向订阅同一课次的浏览器发送 refresh 事件，并清理已经断开的连接。 */
  void update(long sid) {
    for (SseEmitter e : subscribers.getOrDefault(sid, Set.of()))
      try {
        e.send(SseEmitter.event().name("refresh").data(sid));
      } catch (Exception ex) {
        subscribers.get(sid).remove(e);
      }
  }

  /** 健康检查同时验证 Web 服务和 MySQL 连接是否可用。 */
  @GetMapping("/health")
  Map<String, Object> health() {
    school.databaseAvailable();
    return ok(
        Map.of(
            "database",
            "MySQL",
            "serverTime",
            school.now().atOffset(ZoneOffset.ofHours(8)).toString()));
  }

  /** 登录成功后返回随机 Token 与当前用户资料；密码校验和限流由服务层处理。 */
  @PostMapping("/auth/login")
  Map<String, Object> login(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return ok(school.login(b, r.getRemoteAddr()));
  }

  /** 注册学生或持有邀请码的教师账号。 */
  @PostMapping("/auth/register")
  Map<String, Object> register(@RequestBody Map<String, Object> b) {
    school.register(b);
    return ok(null);
  }

  /** 记录密码重置申请；本地实训版不直接通过短信或邮件重置密码。 */
  @PostMapping("/auth/reset-request")
  Map<String, Object> reset(@RequestBody Map<String, Object> b) {
    String account = SchoolService.text(b, "account");
    SchoolService.require(account.matches("[A-Za-z0-9]{6,20}"), 400, "账号格式无效");
    school.requestPasswordReset(account);
    return ok(Map.of("message", "已登记，请携带学号联系学校管理员核验重置。当前未接通短信或邮件。"));
  }

  /** 返回当前 Bearer Token 对应的用户资料。 */
  @GetMapping("/me")
  Map<String, Object> me(HttpServletRequest r) {
    return ok(user(r));
  }

  /** 删除当前 Token，使浏览器保存的会话立即失效。 */
  @PostMapping("/logout")
  Map<String, Object> logout(HttpServletRequest r) {
    school.logout(r.getHeader("Authorization"));
    return ok(null);
  }

  /** 查询当前用户权限范围内的一周课表。 */
  @GetMapping("/timetable")
  Map<String, Object> timetable(@RequestParam String weekStart, HttpServletRequest r) {
    LocalDate start = LocalDate.parse(weekStart);
    return ok(school.timetable(user(r), start, start.plusDays(6)));
  }

  /** 返回当前服务端时间正在进行的课次，供首页和课堂页定位当前课堂。 */
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

  /** 教师修改实际开始/结束时间；服务层提交后会重排该课次的 30 分钟缺勤检查。 */
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

  /** 学生上报或取消举手，随后通知同课次座位图刷新。 */
  @PostMapping("/lesson-sessions/{sid}/hand-raises")
  Map<String, Object> hand(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    SchoolService.require(b.get("raised") instanceof Boolean, 400, "举手状态须为布尔值");
    school.hand(user(r), sid, (Boolean) b.get("raised"));
    update(sid);
    return ok(null);
  }

  /** 返回当前用户可见的课堂消息：群消息加上本人参与的私信。 */
  @GetMapping("/lesson-sessions/{sid}/messages")
  Map<String, Object> messages(@PathVariable long sid, HttpServletRequest r) {
    return ok(school.messages(user(r), sid));
  }

  /** 发送群消息或私信，成功后通过 SSE 通知课堂页面刷新。 */
  @PostMapping("/lesson-sessions/{sid}/messages")
  Map<String, Object> message(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    school.message(user(r), sid, b);
    update(sid);
    return ok(null);
  }

  /** 教师为本班学生保存课堂评分，并向同课次页面推送刷新。 */
  @PostMapping("/lesson-sessions/{sid}/scores")
  Map<String, Object> score(
      @PathVariable long sid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    school.score(user(r), sid, b);
    update(sid);
    return ok(null);
  }

  /** 建立仅持续一分钟的 SSE 连接；浏览器收到 ready 后保持连接，收到 refresh 后重新拉取课堂数据。 */
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

  /** 学生在选定日期区间内查询可关联到请假申请的具体课次。 */
  @GetMapping("/leave/eligible-sessions")
  Map<String, Object> eligible(
      @RequestParam String startDate, @RequestParam String endDate, HttpServletRequest r) {
    SchoolService.role(user(r), "student");
    return ok(school.timetable(user(r), LocalDate.parse(startDate), LocalDate.parse(endDate)));
  }

  /** 提交请假申请及其明确选中的课次列表。 */
  @PostMapping("/leave/applications")
  Map<String, Object> apply(@RequestBody Map<String, Object> b, HttpServletRequest r) {
    return ok(school.apply(user(r), b));
  }

  /** 查询当前用户本人可见的请假记录和审批进度。 */
  @GetMapping("/leave/applications/mine")
  Map<String, Object> mine(HttpServletRequest r) {
    return ok(school.applications(user(r), false));
  }

  /** 查询当前审批人的待办申请；服务层过滤审批阶段与权限范围。 */
  @GetMapping("/approvals/pending")
  Map<String, Object> pending(HttpServletRequest r) {
    SchoolService.role(user(r), "teacher", "vice", "dean");
    return ok(school.applications(user(r), true));
  }

  /** 写入审批决定，并刷新申请覆盖的每一个课次页面。 */
  @PostMapping("/approvals/{aid}/decision")
  Map<String, Object> decide(
      @PathVariable long aid, @RequestBody Map<String, Object> b, HttpServletRequest r) {
    school.decide(user(r), aid, b);
    for (long sessionId : school.applicationSessionIds(aid)) update(sessionId);
    return ok(null);
  }

  /** 按日期区间与角色范围导出考勤明细，可选按学生账号或姓名筛选。 */
  @GetMapping("/reports/attendance")
  Map<String, Object> report(
      @RequestParam String startDate,
      @RequestParam String endDate,
      @RequestParam(defaultValue = "") String student,
      HttpServletRequest r) {
    return ok(
        school.report(user(r), LocalDate.parse(startDate), LocalDate.parse(endDate), student));
  }

  /** 返回当前用户最近 100 条站内通知。 */
  @GetMapping("/notifications")
  Map<String, Object> notifications(HttpServletRequest r) {
    return ok(school.notifications(user(r)));
  }

  /** 上传请假材料：先检查大小、文件头和真实图片可读性，再把二进制内容保存到私有 attachments 表。 */
  @PostMapping("/attachments")
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
    long id = school.saveAttachment(user(r), name, png ? "image/png" : "image/jpeg", bytes);
    return ok(Map.of("id", id, "name", name));
  }

  /** 读取附件时校验“本人上传”或“当前审批人对关联申请可见”，并禁止浏览器缓存私有凭证图片。 */
  @GetMapping("/attachments/{aid}")
  ResponseEntity<byte[]> image(@PathVariable long aid, HttpServletRequest r) {
    Map<String, Object> f = school.attachment(user(r), aid);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType((String) f.get("media_type")))
        .header("Cache-Control", "no-store")
        .body((byte[]) f.get("content"));
  }
}
