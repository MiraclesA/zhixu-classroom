package cn.zhixu.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 知序课堂的数据访问层。
 *
 * <p>本类是 MySQL 的唯一技术入口：它封装 {@link JdbcTemplate}，执行参数化查询和写入，并让控制器、服务层不再依赖 Spring JDBC 的具体实现。
 * 业务规则、角色校验和事务边界仍属于 service 层。
 */
@Repository
public class SchoolRepository {
  private final JdbcTemplate jdbc;

  public SchoolRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 执行可能返回多条记录的参数化查询。 */
  public List<Map<String, Object>> queryForList(String sql, Object... args) {
    return jdbc.queryForList(sql, args);
  }

  /** 读取一个标量值，例如 COUNT、MAX 或最近插入记录的 ID。 */
  public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
    return jdbc.queryForObject(sql, requiredType, args);
  }

  /** 执行 INSERT、UPDATE、DELETE 等数据变更并返回受影响行数。 */
  public int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  /** 供服务层健康检查确认当前数据源可连接、可执行基本查询。 */
  public boolean isAvailable() {
    return Integer.valueOf(1).equals(queryForObject("SELECT 1", Integer.class));
  }
}
