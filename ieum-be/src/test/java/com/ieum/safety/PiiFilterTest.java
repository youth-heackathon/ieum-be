package com.ieum.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PiiFilterTest {

    @Test
    void stripsPii() {
        assertEquals("주민번호 [주민번호]이고 [주민번호], [주민번호]",
                PiiFilter.strip("주민번호 740302-2345678이고 7403022345678, 740302-2******"));
        assertEquals("연락은 [전화번호] / [전화번호] / [전화번호] / [전화번호]로",
                PiiFilter.strip("연락은 010-1234-5678 / 010 1234 5678 / 01012345678 / +82 10-1234-5678로"));
        assertEquals("집 [전화번호]", PiiFilter.strip("집 02-123-4567"));
        assertEquals("메일은[이메일] 이에요", PiiFilter.strip("메일은jieun.park+care@example.co.kr 이에요"));
    }

    @Test
    void keepsCareSentences() {
        String care = "아침 8시 30분에 0.5알, 1일 3회 드세요. 2026-11-21 병원 예약, 대표번호 1588-1234, 혈압 120/80";
        assertEquals(care, PiiFilter.strip(care));
    }
}
