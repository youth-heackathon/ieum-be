package com.ieum.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IP 해시")
class IpHasherTest {

    @Test
    @DisplayName("같은 IP는 같은 해시, 다른 IP는 다른 해시가 된다")
    void stable() {
        IpHasher h = new IpHasher("salt");
        assertEquals(h.hash("1.2.3.4"), h.hash("1.2.3.4"));
        assertNotEquals(h.hash("1.2.3.4"), h.hash("1.2.3.5"));
    }

    @Test
    @DisplayName("해시에서 원래 IP가 드러나지 않는다")
    void opaque() {
        String hash = new IpHasher("salt").hash("1.2.3.4");
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertFalse(hash.contains("1.2.3.4"));
    }

    @Test
    @DisplayName("비밀값이 다르면 같은 IP도 다른 해시가 된다")
    void salted() {
        assertNotEquals(new IpHasher("a").hash("1.2.3.4"), new IpHasher("b").hash("1.2.3.4"));
    }

    @Test
    @DisplayName("비밀값이 비어 있어도 임시 값으로 동작한다")
    void blankSalt() {
        IpHasher h = new IpHasher("");
        assertEquals(h.hash("1.2.3.4"), h.hash("1.2.3.4"));
        assertNotEquals(new IpHasher("").hash("1.2.3.4"), h.hash("1.2.3.4"));
    }
}
