package com.hsmap.factverification.shared;

/**
 * 工作台统一业务异常。
 *
 * <p>code 用于 API、报告与自动测试稳定判断；描述只能包含已脱敏的用户可理解信息，内部堆栈由日志系统单独处理。
 */
public class ServiceException extends RuntimeException {

    private final String code;

    /** 创建带稳定错误码的业务异常。 */
    public ServiceException(String code, String description) {
        super(code + ": " + ErrorSanitizer.sanitize(description));
        this.code = code;
    }

    /** 返回跨版本稳定的错误码。 */
    public String getCode() {
        return code;
    }
}
