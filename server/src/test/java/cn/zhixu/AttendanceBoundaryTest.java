package cn.zhixu;

import static org.junit.jupiter.api.Assertions.*;

import cn.zhixu.common.ApiError;
import cn.zhixu.service.SchoolService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AttendanceBoundaryTest {
  @Test
  void preciseBoundaries() {
    LocalDateTime start = LocalDateTime.of(2026, 10, 14, 10, 10);
    assertThrows(ApiError.class, () -> SchoolService.attendanceAt(start, start.minusSeconds(1)));
    assertEquals("PRESENT", SchoolService.attendanceAt(start, start.plusMinutes(5)));
    assertEquals("LATE", SchoolService.attendanceAt(start, start.plusMinutes(5).plusSeconds(1)));
    assertEquals("LATE", SchoolService.attendanceAt(start, start.plusMinutes(30)));
    assertThrows(
        ApiError.class,
        () -> SchoolService.attendanceAt(start, start.plusMinutes(30).plusSeconds(1)));
  }
}
