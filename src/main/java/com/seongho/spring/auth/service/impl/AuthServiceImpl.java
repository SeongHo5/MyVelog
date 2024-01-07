package com.seongho.spring.auth.service.impl;

import com.seongho.spring.account.entity.Account;
import com.seongho.spring.account.enums.UserStatus;
import com.seongho.spring.account.service.AccountService;
import com.seongho.spring.auth.dto.SignInRequestDto;
import com.seongho.spring.auth.dto.SignInResponseDto;
import com.seongho.spring.auth.service.AuthService;
import com.seongho.spring.common.exception.AuthException;
import com.seongho.spring.common.exception.NotFoundException;
import com.seongho.spring.common.jwt.JwtProvider;
import com.seongho.spring.common.jwt.dto.JwtReissueResponseDto;
import com.seongho.spring.common.jwt.dto.JwtRequestDto;
import com.seongho.spring.common.jwt.dto.JwtResponseDto;
import com.seongho.spring.common.service.RedisService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.seongho.spring.account.enums.UserStatus.DELETED;
import static com.seongho.spring.account.enums.UserStatus.RESTRICTED;
import static com.seongho.spring.common.constant.AuthConstant.*;
import static com.seongho.spring.common.exception.enums.ExceptionStatus.*;
import static com.seongho.spring.common.utils.CookieUtil.createCookie;
import static org.springframework.beans.propertyeditors.CustomBooleanEditor.VALUE_TRUE;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RedisService redisService;


    /**
     * 로그인 처리를 위해 사용자를 조회하고, 비밀번호를 검증한다.
     *
     * @param signInRequestDto 로그인 요청 정보(E-mail, PW)
     * @return 로그인 응답 정보(토큰, 토큰 유효기간, 사용자 이름, 사용자 권한)
     */
    @Override
    @Transactional
    public ResponseEntity<SignInResponseDto> signIn(
            final SignInRequestDto signInRequestDto
    ) {
        String email = signInRequestDto.email();
        String requestedPassword = signInRequestDto.password();
        Account account = accountService.findAccountByEmail(email);

        checkUserStatusByEmail(account);
        checkPasswordIsCorrect(requestedPassword, account);

        final JwtResponseDto jwtResponseDto = jwtProvider.createJwtToken(email);
        redisService.setDataExpire(email, jwtResponseDto.refreshToken(),
                REFRESH_TOKEN_EXPIRE_TIME);

        return ResponseEntity
                .ok()
                .header(HttpHeaders.SET_COOKIE, createCookie(AUTHORIZATION_KEY, jwtResponseDto.refreshToken()).toString())
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + jwtResponseDto.accessToken())
                .body(
                        SignInResponseDto.builder()
                                .userName(account.getName())
                                .userRole(account.getUserRole())
                                .grantType(jwtResponseDto.grantType())
                                .accessToken(jwtResponseDto.accessToken())
                                .refreshToken(jwtResponseDto.refreshToken())
                                .expiresIn(jwtResponseDto.accessTokenExpiresIn())
                                .build()
                );
    }

    /**
     * 로그아웃 처리를 위해 사용자 인증 수단(토큰)을 검증하고, 무효화한다.
     *
     * @param jwtRequestDto 인증 수단(토큰)
     */
    @Override
    @Transactional
    public void signOut(final JwtRequestDto jwtRequestDto) {
        jwtProvider.validateToken(jwtRequestDto.getAccessToken());

        Claims claims = jwtProvider.getInfoFromToken(jwtRequestDto.getAccessToken());
        String email = claims.getSubject();

        invalidateToken(email, jwtRequestDto.getAccessToken());
    }

    /**
     * 토큰 재발급
     *
     * @param jwtRequestDto 기존 토큰
     * @return 재발급된 토큰
     */
    @Override
    @Transactional
    public ResponseEntity<JwtReissueResponseDto> reissue(final JwtRequestDto jwtRequestDto) {

        validateRefreshToken(jwtRequestDto);
        validateRefreshTokenOwnership(jwtRequestDto);

        String email = jwtProvider.getInfoFromToken(jwtRequestDto.getAccessToken()).getSubject();

        JwtResponseDto jwtResponseDto = jwtProvider.createJwtToken(email);

        return ResponseEntity
                .ok()
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + jwtResponseDto.accessToken())
                .body(
                        JwtReissueResponseDto.builder()
                                .accessToken(jwtResponseDto.accessToken())
                                .refreshToken(jwtRequestDto.getRefreshToken())
                                .accessTokenExpiresIn(jwtResponseDto.accessTokenExpiresIn())
                                .build()
                );
    }

    // =============== PRIVATE METHODS =============== //

    /**
     * 계정의 상태를 검증한다.
     *
     * @param account 계정 정보
     * @throws NotFoundException 계정이 삭제(DELETED) 상태일 경우
     * @throws AuthException     계정이 제한(RESTRICTED) 상태일 경우
     * @see UserStatus
     */
    protected void checkUserStatusByEmail(Account account) {
        if (account.getUserStatus().equals(DELETED)) {
            throw new NotFoundException(DELETED_ACCOUNT);
        }
        if (account.getUserStatus().equals(RESTRICTED)) {
            throw new AuthException(RESTRICTED_ACCOUNT);
        }
    }

    /**
     * 요청된 비밀번호와 계정의 비밀번호가 일치하는지 검증한다.
     */
    private void checkPasswordIsCorrect(String requestedPassword, Account account) {
        if (!passwordEncoder.matches(requestedPassword, account.getPassword())) {
            throw new AuthException(INVALID_ID_OR_PW);
        }
    }

    /**
     * 토큰의 유효성을 검증한다.
     */
    private void validateRefreshToken(JwtRequestDto jwtRequestDto) {
        jwtProvider.validateToken(jwtRequestDto.getRefreshToken());
    }

    /**
     * 요청자와 토큰의 소유자 정보가 일치하는지 검증한다.
     */
    private void validateRefreshTokenOwnership(JwtRequestDto jwtRequestDto) {
        String email = jwtProvider.getInfoFromToken(jwtRequestDto.getAccessToken()).getSubject();
        String validRefreshToken = redisService.getData(email);
        if (!jwtRequestDto.getRefreshToken().equals(validRefreshToken)) {
            throw new AuthException(EXPIRED_TOKEN);
        }
    }

    /**
     * REDIS에서 사용자의 정보를 삭제하고, 토큰을 BLACK_LIST에 추가해 토큰을 무효화한다.
     */
    private void invalidateToken(String email, String accessToken) {
        redisService.deleteData(email);
        redisService.setDataExpire(
                BLACK_LIST_KEY_PREFIX + accessToken,
                VALUE_TRUE, ACCESS_TOKEN_EXPIRE_TIME / 1000L);
    }

}
