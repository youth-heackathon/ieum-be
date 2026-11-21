package com.ieum.safety;

import java.util.regex.Pattern;

/** AI로 보내기 전에 주민번호·전화번호·이메일 형식을 지운다 (FR-11.1). */
public final class PiiFilter {

    // 앞뒤가 숫자면 매치하지 않는다 → "0.5알", "2026-11-21", "8시 30분" 같은 돌봄 문장은 그대로 둔다
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}\\s*-?\\s*[1-8][\\d*]{6}(?![\\d*])");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+82[-.\\s]?|0)\\d{1,2}[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    private PiiFilter() {}

    public static String strip(String text) {
        String t = RRN.matcher(text).replaceAll("[주민번호]");
        t = PHONE.matcher(t).replaceAll("[전화번호]");
        return EMAIL.matcher(t).replaceAll("[이메일]");
    }
}
