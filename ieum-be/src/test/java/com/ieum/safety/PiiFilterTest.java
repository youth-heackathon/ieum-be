package com.ieum.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PII 필터: AI로 보내기 전에 지운다")
class PiiFilterTest {

    @Test
    @DisplayName("주민번호는 하이픈·붙여쓰기·뒷자리 가림 모두 지운다")
    void rrn() {
        assertEquals("[주민번호]", PiiFilter.strip("740302-2345678"));
        assertEquals("[주민번호]", PiiFilter.strip("7403022345678"));
        assertEquals("[주민번호]", PiiFilter.strip("740302 - 2******"));
    }

    @Test
    @DisplayName("휴대폰 번호는 하이픈·공백·붙여쓰기·+82 모두 지운다")
    void mobile() {
        assertEquals("[전화번호]", PiiFilter.strip("010-1234-5678"));
        assertEquals("[전화번호]", PiiFilter.strip("010 1234 5678"));
        assertEquals("[전화번호]", PiiFilter.strip("01012345678"));
        assertEquals("[전화번호]", PiiFilter.strip("010.123.5678"));
        assertEquals("[전화번호]", PiiFilter.strip("+82 10-1234-5678"));
    }

    @Test
    @DisplayName("유선·인터넷 전화번호도 지운다")
    void landline() {
        assertEquals("[전화번호]", PiiFilter.strip("02-123-4567"));
        assertEquals("[전화번호]", PiiFilter.strip("031-1234-5678"));
        assertEquals("[전화번호]", PiiFilter.strip("070-1234-5678"));
    }

    @Test
    @DisplayName("이메일을 지우고 붙어 있는 한글은 남긴다")
    void email() {
        assertEquals("메일은[이메일] 이에요", PiiFilter.strip("메일은jieun.park+care@example.co.kr 이에요"));
    }

    @Test
    @DisplayName("한 문장에 여러 개가 섞여도 모두 지운다")
    void mixed() {
        assertEquals("어머니 [주민번호], 연락 [전화번호] 또는 [이메일]로 주세요",
                PiiFilter.strip("어머니 460903-2123456, 연락 010-1234-5678 또는 a@b.kr로 주세요"));
    }

    @Test
    @DisplayName("돌봄 문장의 숫자(용량·시각·날짜·횟수·혈압·대표번호)는 건드리지 않는다")
    void keepsCareNumbers() {
        String care = "아침 8시 30분에 0.5알, 1일 3회 드세요. 2026-11-21 병원 예약, 혈압 120/80, 대표번호 1588-1234, 체중 52.5kg";
        assertEquals(care, PiiFilter.strip(care));
    }

    @Test
    @DisplayName("빈 문자열은 그대로 돌려준다")
    void empty() {
        assertEquals("", PiiFilter.strip(""));
    }
}
