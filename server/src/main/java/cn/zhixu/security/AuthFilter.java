package cn.zhixu.security;

import cn.zhixu.service.SchoolService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REST API 的统一认证入口。
 *
 * <p>除健康检查和登录/注册外，所有 {@code /api/v1/**} 请求都必须携带 Bearer Token。 Token 只保存其 SHA-256 摘要；校验成功后把当前用户资料写入
 * request attribute，供控制器和服务层继续做角色、班级或学院范围授权。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
  private final JdbcTemplate db;
  private final Clock clock;

  public AuthFilter(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** 跳过公开接口；其余请求查询未过期 Token 与已启用用户。认证失败立即返回 401，后续 Controller 不会被调用。 */
  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String path = req.getRequestURI();
    if (!path.startsWith("/api/v1/")
        || path.equals("/api/v1/health")
        || path.startsWith("/api/v1/auth/")) {
      chain.doFilter(req, res);
      return;
    }
    String token = req.getHeader("Authorization");
    if (token != null && token.startsWith("Bearer ")) {
      List<Map<String, Object>> found =
          db.queryForList(
              "SELECT u.id,u.account,u.name,u.role,u.dept_id,u.class_id,u.seat_no FROM tokens t"
                  + " JOIN users u ON u.id=t.user_id WHERE t.token_hash=? AND t.expires_at>? AND"
                  + " u.enabled=TRUE",
              SchoolService.hash(token.substring(7)),
              LocalDateTime.now(clock));
      if (!found.isEmpty()) {
        req.setAttribute("user", found.get(0));
        chain.doFilter(req, res);
        return;
      }
    }
    res.setStatus(401);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            "{\"code\":401,\"message\":\"请重新登录\",\"data\":{},\"requestId\":\""
                + UUID.randomUUID()
                + "\"}");
  }
}
