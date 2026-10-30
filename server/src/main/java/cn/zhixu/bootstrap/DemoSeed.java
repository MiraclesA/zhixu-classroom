package cn.zhixu.bootstrap;

import cn.zhixu.repository.SchoolRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可选的本地实训数据初始化器。
 *
 * <p>仅当 {@code zhixu.demo=true} 且 users 表为空时运行；因此正常重启不会覆盖老师演示时修改的数据库数据。
 */
@Component
class DemoSeed implements ApplicationRunner {
  private final SchoolRepository db;
  private final Clock clock;
  private final boolean demo;

  DemoSeed(SchoolRepository db, Clock clock, @Value("${zhixu.demo}") boolean demo) {
    this.db = db;
    this.clock = clock;
    this.demo = demo;
  }

  /**
   * 在一个事务中写入学院、班级、演示账号、课程和覆盖前后若干周的课次。
   *
   * <p>最后额外插入一节正在进行的外院课程，用于验证权限范围隔离。
   */
  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!demo || db.queryForObject("SELECT COUNT(*) FROM users", Integer.class) > 0) return;
    db.update("INSERT INTO departments VALUES(1,'信息工程学院'),(2,'外语学院')");
    db.update("INSERT INTO classes VALUES(1,1,'24软件技术1班','软件技术',2024),(2,2,'24英语1班','英语',2024)");
    String pw = new BCryptPasswordEncoder().encode("123456");
    String[] names = {
      "林同学", "王小雨", "张三", "赵一鸣", "李佳", "陈明", "周浩", "刘婷", "吴悦", "杨帆", "何欣", "李健", "黄诗涵", "许文博", "罗阳",
      "钟楚", "叶涵", "余翔", "范可", "郑怡", "林思雨", "朱英", "郭浩然", "陈程", "王悦", "苏明", "孔颖", "田小雅", "邓熙", "唐扬",
      "曹旭", "徐文", "赵雨", "彭雅", "刘涛", "陈洁", "何晨", "欧阳雪", "许楠", "袁辰"
    };
    for (int i = 0; i < names.length; i++)
      db.update(
          "INSERT INTO users(id,account,name,password_hash,role,dept_id,class_id,seat_no)"
              + " VALUES(?,?,?,?, 'student',1,1,?)",
          i + 1,
          "2024" + String.format("%04d", 101 + i),
          names[i],
          pw,
          i);
    String[][] staff = {
      {"T2026001", "陈老师", "teacher"},
      {"C2026001", "周老师", "counselor"},
      {"V2026001", "李副院长", "vice"},
      {"D2026001", "张院长", "dean"}
    };
    for (int i = 0; i < staff.length; i++)
      db.update(
          "INSERT INTO users(id,account,name,password_hash,role,dept_id) VALUES(?,?,?,?,?,1)",
          101 + i,
          staff[i][0],
          staff[i][1],
          pw,
          staff[i][2]);
    db.update(
        "INSERT INTO users(id,account,name,password_hash,role,dept_id,class_id,seat_no)"
            + " VALUES(200,'OTHER001','外院学生',?,'student',2,2,0),(201,'OTHERTEACHER','外院教师',?,'teacher',2,NULL,NULL),(202,'OTHERVICE','外院分院长',?,'vice',2,NULL,NULL)",
        pw,
        pw,
        pw);
    db.update("INSERT INTO rosters VALUES('20249999','新同学',1,FALSE)");
    String[] courses = {"Java Web 应用开发", "计算机网络基础", "数据库原理与应用", "软件工程", "大学英语"};
    for (int i = 0; i < 5; i++)
      db.update(
          "INSERT INTO courses VALUES(?,?,101,1,?)",
          i + 1,
          courses[i],
          i == 0 ? "实训楼 A302" : "教学楼 B" + (203 + i));
    db.update("INSERT INTO courses VALUES(6,'外院课程',201,2,'外语楼201')");
    String[] starts = {"08:20", "09:15", "10:10", "11:05", "14:30", "15:25", "16:20", "17:15"};
    int[][] slots = {
      {0, 0, 1, 1, 3, 3, -1, -1},
      {2, 2, -1, -1, 4, 4, -1, -1},
      {1, 1, 0, 0, 2, 2, -1, -1},
      {3, 3, 4, 4, -1, -1, -1, -1},
      {0, 0, -1, -1, 1, 1, -1, -1},
      {-1, -1, -1, -1, -1, -1, -1, -1},
      {-1, -1, -1, -1, -1, -1, -1, -1}
    };
    LocalDate today = LocalDate.now(clock);
    for (int n = -14; n < 121; n++) {
      LocalDate day = today.plusDays(n);
      int w = day.getDayOfWeek().getValue() - 1;
      for (int p = 0; p < 8; p++)
        if (slots[w][p] >= 0) {
          LocalDateTime start = day.atTime(LocalTime.parse(starts[p]));
          db.update(
              "INSERT INTO lessons(course_id,lesson_date,period_no,start_at,end_at)"
                  + " VALUES(?,?,?,?,?)",
              slots[w][p] + 1,
              day,
              p,
              start,
              start.plusMinutes(45));
        }
    }
    LocalDateTime start = LocalDateTime.now(clock).minusMinutes(2);
    db.update(
        "INSERT INTO lessons(course_id,lesson_date,period_no,start_at,end_at) VALUES(?,?,?,?,?)",
        6,
        today,
        0,
        start,
        start.plusMinutes(45));
  }
}
