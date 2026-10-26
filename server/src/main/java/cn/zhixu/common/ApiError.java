package cn.zhixu.common;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

public class ApiError extends RuntimeException {
  final int status;

  public ApiError(int status, String text) {
    super(text);
    this.status = status;
  }
}

@RestControllerAdvice
class ErrorHandler {
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

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(new ApiError(400, "请求参数无效"));
  }

  @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
  ResponseEntity<?> conflict(Exception e) {
    return error(new ApiError(409, "重复记录或并发操作冲突，请刷新"));
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  ResponseEntity<?> upload(Exception e) {
    return error(new ApiError(413, "图片不能超过 5MB"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    org.slf4j.LoggerFactory.getLogger(ErrorHandler.class)
        .error("API failure: {}", e.getClass().getSimpleName());
    return error(new ApiError(500, "服务处理失败，请查看本机服务日志"));
  }
}
