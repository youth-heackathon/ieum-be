package com.ieum.safety;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/** IP는 원문 대신 HMAC-SHA256 해시로만 저장한다. */
@Component
public class IpHasher {

    private static final Logger log = LoggerFactory.getLogger(IpHasher.class);
    private final SecretKeySpec key;

    public IpHasher(@Value("${IP_HASH_SALT:}") String salt) {
        if (salt.isBlank()) {
            log.warn("IP_HASH_SALT가 비어 있어 임시 값을 씁니다. 재시작하면 IP별 횟수가 초기화돼요");
            salt = UUID.randomUUID().toString();
        }
        this.key = new SecretKeySpec(salt.getBytes(UTF_8), "HmacSHA256");
    }

    /** forward-headers-strategy 설정 덕에 프록시 뒤에서도 실제 접속 IP를 쓴다. */
    public String hash(HttpServletRequest request) {
        return hash(request.getRemoteAddr());
    }

    public String hash(String ip) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
