package com.ieum;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.springframework.http.HttpStatus.CONFLICT;

/** 고유 제약 위반(중복 가입, 이미 연결됨 등)을 500 대신 409로. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate() {
        return ProblemDetail.forStatusAndDetail(CONFLICT, "이미 등록된 정보예요");
    }
}
