package cn.zhixu.common;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 业务校验失败时使用的受控异常。
 *
 * <p>服务层只需要说明 HTTP 状态码和面向用户的原因；{@link ErrorHandler} 会把它转换为项目统一的 JSON 响应， 避免把数据库或 Java 异常细节暴露给前端。
 */
public class ApiError extends RuntimeException {
  final int status;

  public ApiError(int status, String text) {
    super(text);
    this.status = status;
  }
}

/** 将业务异常、参数错误和基础设施异常映射为统一 API 响应的全局异常处理器。 */
@RestControllerAdvice
class ErrorHandler {
  /** 返回调用方可处理的业务错误，例如越权、状态冲突或输入校验失败。 */
  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> error(ApiError e) {
    return ResponseEntity.status(e.status)
        .body(
            Map.of(
                "code",
                e.status,
                "message",
                e.getMessage(),
                "data",
                Map.of(),
                "requestId",
                UUID.randomUUID().toString()));
  }

  /** 将 Spring 绑定参数失败统一收敛为 400，避免各控制器重复 try/catch。 */
  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(new ApiError(400, "请求参数无效"));
  }

  /** 将唯一键冲突转换为前端可理解的“重复或并发冲突”。 */
  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<?> conflict(Exception e) {
    return error(new ApiError(409, "重复记录或并发操作冲突，请刷新"));
  }

  /** 对超出配置上限的附件返回明确的上传失败提示。 */
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> upload(Exception e) {
    return error(new ApiError(413, "图片不能超过 5MB"));
  }

  /** 记录未预期异常类型，但不向浏览器泄露内部堆栈或数据库结构。 */
  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ErrorHandler.class)
        .error("API failure: {}", e.getClass().getSimpleName());
    return error(new ApiError(500, "服务处理失败，请查看本机服务日志"));
  }
}
