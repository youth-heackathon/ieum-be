package com.ieum.verify;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원문 인용 검증 (FR-5.4). 외부 API 없이 규칙만 쓴다.
 * <ol>
 *   <li>인용문이 원문에 그대로 있는가 (공백·문장부호를 지우고 비교) → 없으면 blocked</li>
 *   <li>정리문의 숫자가 인용문에 있는가 ("0.5알", "반 알", "한 번", "5시") → 다르면 review</li>
 *   <li>부정 표현(안·않·못·없·말·아니·금지)이 양쪽에 똑같이 있는가 → 다르면 review</li>
 * </ol>
 */
public final class QuoteVerifier {

    public enum Status { pass, review, blocked }

    public record Result(Status status, String reason) {}

    private static final Pattern DIGITS = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** 세는 말 앞의 고유어 수: "반 알", "한 번", "두 알", "세 시" */
    private static final Pattern WORD_NUMBER = Pattern.compile(
            "(반|한|하나|두|둘|세|셋|석|네|넷|다섯|여섯|일곱|여덟|아홉|열)\\s?(알|정|번|시|개|포|봉|스푼|숟가락|잔|회|캡슐|방울|살|명|가지|군데)");
    private static final Pattern DAY_WORD = Pattern.compile("하루|이틀|사흘");
    private static final Map<String, String> WORD_VALUE = Map.ofEntries(
            Map.entry("반", "0.5"), Map.entry("한", "1"), Map.entry("하나", "1"), Map.entry("두", "2"), Map.entry("둘", "2"),
            Map.entry("세", "3"), Map.entry("셋", "3"), Map.entry("석", "3"), Map.entry("네", "4"), Map.entry("넷", "4"),
            Map.entry("다섯", "5"), Map.entry("여섯", "6"), Map.entry("일곱", "7"), Map.entry("여덟", "8"),
            Map.entry("아홉", "9"), Map.entry("열", "10"), Map.entry("하루", "1"), Map.entry("이틀", "2"), Map.entry("사흘", "3"));
    /** "불안", "안정제", "안전"은 부정이 아니다: '안'은 앞에 글자가 없고 띄어 쓰거나 흔한 동사 앞일 때만 */
    private static final Pattern NEGATION = Pattern.compile(
            "않|없|아니|금지|지\\s?(?:마|말)|못(?=\\s?[가-힣])"
                    + "|(?<![가-힣])안(?:\\s+[가-힣]|(?=돼|되|먹|드|해|하|가|와|주|줘|좋|맞|나와|들|보|자|씻|입|움직|일어|마시|마셔|싸|쓰|타|오|아프|아파))");

    private QuoteVerifier() {}

    public static Result verify(String original, String summary, String quote) {
        if (quote == null || normalize(quote).isEmpty())
            return new Result(Status.blocked, "원문 인용이 없어 저장을 막았어요");
        if (!normalize(original).contains(normalize(quote)))
            return new Result(Status.blocked, "원문에 없는 말이라 저장을 막았어요");
        if (normalize(quote).length() < 4)
            return new Result(Status.review, "원문 인용이 너무 짧아요. 원문과 맞는지 확인해 주세요");

        Set<String> missing = numbers(summary);
        missing.removeAll(numbers(quote));
        if (!missing.isEmpty())
            return new Result(Status.review, "숫자가 원문과 달라요 (" + String.join(", ", missing) + "). 확인해 주세요");
        if (negated(summary) != negated(quote))
            return new Result(Status.review, "'안·않·없' 같은 부정 표현이 원문과 달라요. 확인해 주세요");
        return new Result(Status.pass, null);
    }

    /** 한글·영문·숫자만 남긴다. */
    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^가-힣a-z0-9]", "");
    }

    static Set<String> numbers(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        Matcher m = DIGITS.matcher(s);
        while (m.find()) out.add(new BigDecimal(m.group()).stripTrailingZeros().toPlainString());
        m = WORD_NUMBER.matcher(s);
        while (m.find()) out.add(WORD_VALUE.get(m.group(1)));
        m = DAY_WORD.matcher(s);
        while (m.find()) out.add(WORD_VALUE.get(m.group()));
        return out;
    }

    static boolean negated(String s) {
        return s != null && NEGATION.matcher(s).find();
    }
}
