package com.ieum.verify;

import com.ieum.ApiDocs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

import static com.ieum.verify.QuoteVerifier.Status.pass;

/** 평가셋으로 인용 검증기를 돌린다 (FR-5.11). 체험 끝 화면의 "40건 중 n건 차단" 수치. */
@Tag(name = ApiDocs.PUBLIC)
@RestController
public class VerifyController {

    /** AI가 틀린 40건(mistake)과 맞게 정리한 10건(correct). 형식: 종류|원문|정리문|인용문 */
    private static final String CASES = """
            mistake|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요.|아스피린 알레르기가 있어요|아스피린 먹고 두드러기가 났어요
            mistake|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요.|진정제를 드리면 가라앉아요|진정제를 드리면 금방 가라앉아요
            mistake|와파린은 아침 식후에 반 알 드세요.|와파린은 저녁에 드세요|와파린은 저녁 식후에 드세요
            mistake|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요.|아침마다 나가려고 하세요|아침마다 나가려고 하세요
            mistake|자몽 주스는 드시면 안 돼요.|우유는 드시면 안 돼요|우유는 드시면 안 돼요
            mistake|밤에 한 번은 꼭 화장실에 모시고 가 주세요.|밤에 기저귀를 갈아 주세요|밤에 기저귀를 갈아 주세요
            mistake|새우를 드시면 입술이 부어요.|땅콩을 드시면 입술이 부어요|땅콩을 드시면 입술이 부어요
            mistake|혼자 계시면 불을 켜 두세요. 어두우면 무서워하세요.|혼자 계시면 텔레비전을 켜 두세요|혼자 계시면 텔레비전을 켜 두세요
            mistake|틀니는 자기 전에 빼서 물에 담가 두세요.|틀니는 아침에 빼 주세요|틀니는 아침에 빼서 물에 담가 두세요
            mistake|점심은 12시에 드시고 낮잠은 1시간 주무세요.|점심 뒤에 산책을 하세요|점심 뒤에 산책을 하세요
            mistake|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요.|음악을 크게 틀어 드리면 가라앉아요|음악을 크게 틀어 드리면 금방 가라앉아요
            mistake|와파린은 아침 식후에 반 알 드세요.|와파린과 아스피린을 함께 드세요|와파린과 아스피린을 아침 식후에 드세요
            mistake|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요.|주사를 맞으면 열이 나요|주사 맞고 열이 난 적 있어요
            mistake|새우를 드시면 입술이 부어요.|해산물은 모두 피하세요|해산물은 모두 피하세요
            mistake|와파린은 아침 식후에 반 알 드세요.|와파린은 아침 식후에 5알 드세요|와파린은 아침 식후에 반 알 드세요
            mistake|와파린은 아침 식후에 반 알 드세요.|와파린은 아침 식후에 한 알 드세요|와파린은 아침 식후에 반 알 드세요
            mistake|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요.|저녁 6시쯤 나가려고 하세요|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요
            mistake|밤에 한 번은 꼭 화장실에 모시고 가 주세요.|밤에 두 번 화장실에 모시고 가 주세요|밤에 한 번은 꼭 화장실에 모시고 가 주세요
            mistake|점심은 12시에 드시고 낮잠은 1시간 주무세요.|점심은 1시에 드세요|점심은 12시에 드시고
            mistake|점심은 12시에 드시고 낮잠은 1시간 주무세요.|낮잠은 2시간 주무세요|낮잠은 1시간 주무세요
            mistake|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요.|아침 5시쯤 나가려고 하세요|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요
            mistake|와파린은 아침 식후에 반 알 드세요.|와파린은 하루 두 번 드세요|와파린은 아침 식후에 반 알 드세요
            mistake|밤에 한 번은 꼭 화장실에 모시고 가 주세요.|밤에 3번 화장실에 가세요|밤에 한 번은 꼭 화장실에 모시고 가 주세요
            mistake|점심은 12시에 드시고 낮잠은 1시간 주무세요.|점심은 12시, 낮잠은 3시간이에요|점심은 12시에 드시고 낮잠은 1시간 주무세요
            mistake|자몽 주스는 드시면 안 돼요.|자몽 주스를 드셔도 돼요|자몽 주스는 드시면 안 돼요
            mistake|자몽 주스는 드시면 안 돼요.|자몽 주스를 드리세요|자몽 주스는 드시면 안 돼요
            mistake|혼자 계시면 불을 켜 두세요. 어두우면 무서워하세요.|혼자 계셔도 불은 꺼 두세요|혼자 계시면 불을 켜 두세요
            mistake|혼자 계시면 불을 켜 두세요. 어두우면 무서워하세요.|어두워도 무서워하지 않으세요|어두우면 무서워하세요
            mistake|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요.|페니실린 주사는 괜찮아요|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요
            mistake|새우를 드시면 입술이 부어요.|새우를 드셔도 괜찮아요|새우를 드시면 입술이 부어요
            mistake|틀니는 자기 전에 빼서 물에 담가 두세요.|틀니는 빼지 않아도 돼요|틀니는 자기 전에 빼서 물에 담가 두세요
            mistake|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요.|불안해하셔도 손은 잡지 마세요|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요
            mistake|밤에 한 번은 꼭 화장실에 모시고 가 주세요.|밤에는 화장실에 모시고 가지 마세요|밤에 한 번은 꼭 화장실에 모시고 가 주세요
            mistake|자몽 주스는 드시면 안 돼요.|자몽 주스는 꼭 드세요|자몽 주스는 드시면 안 돼요
            mistake|자몽 주스는 드시면 안 돼요.|자몽 주스는 괜찮아요|주스
            mistake|와파린은 아침 식후에 반 알 드세요.|와파린을 끊으세요|반 알
            mistake|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요.|고향의 봄을 틀어 드리세요|고향의 봄
            mistake|틀니는 자기 전에 빼서 물에 담가 두세요.|틀니는 자기 전에 빼 주세요|틀니는 자기 전에 꼭 빼 주세요
            mistake|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요.|페니실린 알레르기가 심해요|페니실린 주사 맞고 온몸에 심한 두드러기가 난 적 있어요
            mistake|점심은 12시에 드시고 낮잠은 1시간 주무세요.|점심은 12시에 드세요|점심은 12시에 꼭 드시고
            correct|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요.|페니실린 주사를 맞으면 두드러기가 나요|페니실린 주사 맞고 온몸에 두드러기가 난 적 있어요
            correct|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요.|불안해하시면 손을 잡고 고향의 봄을 불러 드려요|불안해하시면 손 잡고 고향의 봄 불러 드리면 금방 가라앉아요
            correct|와파린은 아침 식후에 반 알 드세요.|와파린은 아침 식후에 반 알 드세요|와파린은 아침 식후에 반 알 드세요
            correct|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요.|저녁 5시쯤 집에 가야 한다며 나가려고 하세요|저녁 5시쯤 되면 집에 간다고 자꾸 나가려고 하세요
            correct|자몽 주스는 드시면 안 돼요.|자몽 주스는 드시면 안 돼요|자몽 주스는 드시면 안 돼요
            correct|밤에 한 번은 꼭 화장실에 모시고 가 주세요.|밤에 한 번은 화장실에 모셔다 드려요|밤에 한 번은 꼭 화장실에 모시고 가 주세요
            correct|새우를 드시면 입술이 부어요.|새우를 드시면 입술이 부어요|새우를 드시면 입술이 부어요
            correct|혼자 계시면 불을 켜 두세요. 어두우면 무서워하세요.|어두우면 무서워하시니 불을 켜 두세요|혼자 계시면 불을 켜 두세요
            correct|틀니는 자기 전에 빼서 물에 담가 두세요.|자기 전에 틀니를 빼서 물에 담가 주세요|틀니는 자기 전에 빼서 물에 담가 두세요
            correct|점심은 12시에 드시고 낮잠은 1시간 주무세요.|점심은 12시, 낮잠은 1시간이에요|점심은 12시에 드시고 낮잠은 1시간 주무세요
            """;

    record Missed(String summary, String quote) {}

    record Eval(int mistakes, int caught, int blocked, int review, int missed, int correct, int correctPassed,
                String headline, List<Missed> missedCases) {}

    @Operation(summary = "인용 검증 평가셋 결과", description = "AI가 틀린 40건을 검증기에 넣어 몇 건을 잡고 몇 건을 놓쳤는지. 체험 끝 화면에 쓴다.")
    @GetMapping("/api/public/verify/eval")
    public Eval eval() {
        int mistakes = 0, blocked = 0, review = 0, correct = 0, correctPassed = 0;
        var missed = new java.util.ArrayList<Missed>();
        for (String line : CASES.strip().split("\n")) {
            String[] c = line.strip().split("\\|");
            var r = QuoteVerifier.verify(c[1], c[2], c[3]);
            if (c[0].equals("correct")) {
                correct++;
                if (r.status() == pass) correctPassed++;
            } else {
                mistakes++;
                switch (r.status()) {
                    case blocked -> blocked++;
                    case review -> review++;
                    case pass -> missed.add(new Missed(c[2], c[3]));
                }
            }
        }
        int caught = blocked + review;
        return new Eval(mistakes, caught, blocked, review, missed.size(), correct, correctPassed,
                "%d건 중 %d건 차단, %d건 놓침".formatted(mistakes, caught, missed.size()), missed);
    }

    static List<String> lines() {
        return Arrays.asList(CASES.strip().split("\n"));
    }
}
