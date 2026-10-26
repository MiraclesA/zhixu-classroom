package cn.zhixu.model;

/**
 * 考勤记录可保存到 attendance 表的状态。
 *
 * <p>UNREPORTED 仅用于页面展示，表示学生仍处于签到窗口内且数据库中不存在考勤记录， 因此不能持久化。
 */
public enum AttendanceStatus {
  PRESENT,
  LATE,
  ABSENT,
  LEAVE,
  UNREPORTED;

  /** 判断前端提交的状态是否允许写入数据库。 */
  public static boolean isPersistable(String value) {
    try {
      return valueOf(value) != UNREPORTED;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }
}
