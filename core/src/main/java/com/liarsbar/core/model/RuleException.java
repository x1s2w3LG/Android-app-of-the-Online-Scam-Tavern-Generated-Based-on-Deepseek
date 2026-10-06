package com.liarsbar.core.model;

/** 违反规则的客户端操作，code 会透传给前端用于提示。 */
public final class RuleException extends RuntimeException {

    private final String code;

    public RuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
