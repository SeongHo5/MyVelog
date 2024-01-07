package com.seongho.spring.common.constant;

public final class AuthConstant {

    // JWT Constants
    public static final String BLACK_LIST_KEY_PREFIX = "JWT::BLACK_LIST::";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String AUTHORIZATION_KEY = "auth";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final long ACCESS_TOKEN_EXPIRE_TIME = 1000L * 60 * 30; // 30분
    public static final long REFRESH_TOKEN_EXPIRE_TIME = 1000L * 60 * 60 * 24 * 7 * 2; // 2주

    private AuthConstant() {
        throw new IllegalStateException("유틸리티 클래스는 인스턴스화할 수 없습니다.");
    }
}
