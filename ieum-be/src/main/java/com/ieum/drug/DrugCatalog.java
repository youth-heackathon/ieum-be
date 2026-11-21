package com.ieum.drug;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 약 대조: 식약처 의약품 제품 허가정보에서 미리 받은 10종 (시연용, docs/이음수첩_AI_개인정보_약등록.md 2.1).
 * 정리된 약 이름을 가장 비슷한 허가 품목명에 맞추고, 주성분으로 주의 약 종류를 붙인다.
 * ponytail: 10종 고정 목록. 실제 API(DATA_GO_KR_API_KEY)로 바꾸려면 find()만 갈아 끼우면 된다
 */
public final class DrugCatalog {

    public record Drug(String itemSeq, String itemName, String ingredient, String etcOtc, String caution) {}

    private static final List<Drug> DRUGS = List.of(
            new Drug("199701416", "와파린정2밀리그램(와파린나트륨)", "와파린나트륨", "전문의약품", "혈액희석제"),
            new Drug("200808876", "아스피린프로텍트정100밀리그램", "아스피린", "일반의약품", "혈액희석제"),
            new Drug("200003092", "아리셉트정5밀리그램(도네페질염산염)", "도네페질염산염", "전문의약품", null),
            new Drug("201206283", "도네페질정5밀리그램", "도네페질염산염", "전문의약품", null),
            new Drug("199903113", "아모디핀정5밀리그램(암로디핀캠실산염)", "암로디핀캠실산염", "전문의약품", null),
            new Drug("198600432", "다이아벡스정500밀리그램(메트포르민염산염)", "메트포르민염산염", "전문의약품", "당뇨약"),
            new Drug("199500617", "아마릴정2밀리그램(글리메피리드)", "글리메피리드", "전문의약품", "당뇨약 (저혈당 주의)"),
            new Drug("200100912", "쎄로켈정25밀리그램(쿠에티아핀푸마르산염)", "쿠에티아핀푸마르산염", "전문의약품", "진정제"),
            new Drug("199800366", "스틸녹스정10밀리그램(졸피뎀타르타르산염)", "졸피뎀타르타르산염", "전문의약품", "수면제"),
            new Drug("199801754", "타이레놀정500밀리그람(아세트아미노펜)", "아세트아미노펜", "일반의약품", null));

    private DrugCatalog() {}

    /** 이름이 충분히 비슷하면(유사도 0.5 이상) 그 품목. */
    public static Optional<Drug> find(String name) {
        String n = key(name);
        if (n.isEmpty()) return Optional.empty();
        return DRUGS.stream()
                .map(d -> new Object[]{d, Math.max(similarity(n, key(d.itemName())), similarity(n, key(d.ingredient())))})
                .max(Comparator.comparingDouble(o -> (double) o[1]))
                .filter(o -> (double) o[1] >= 0.5)
                .map(o -> (Drug) o[0]);
    }

    /** 용량·단위·제형·괄호(성분)를 떼고 비교한다: "와파린정 2mg" → "와파린" */
    static String key(String s) {
        if (s == null) return "";
        return s.replaceAll("\\(.*?\\)", "")
                .replaceAll("\\d+(\\.\\d+)?\\s*(밀리그램|밀리그람|mg|㎎|g|mcg|마이크로그램)?", "")
                .replaceAll("(서방정|필름코팅정|정|캡슐|시럽|산|액)$", "")
                .replaceAll("[^가-힣a-zA-Z]", "")
                .replaceAll("(정|캡슐)$", "");
    }

    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.contains(b) || b.contains(a)) return 0.9;
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }
}
